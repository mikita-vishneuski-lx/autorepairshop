package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnDelete;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import org.springframework.beans.factory.annotation.Qualifier;

import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.Appointments_;
import cds.gen.repairservice.RepairService_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class AppointmentTotalSumHandler implements EventHandler{

    private static final String DELETED_ITEMS = "deletedAppointmentsItems";

    private final PersistenceService db;
    private final DraftService draftService;

    public AppointmentTotalSumHandler(PersistenceService db,
                                      @Qualifier(RepairService_.CDS_NAME) DraftService draftService) {
        this.db = db;
        this.draftService = draftService;
    }

    @After(event = {CqnService.EVENT_CREATE, CqnService.EVENT_UPDATE},
        entity = AppointmentsItems_.CDS_NAME)
    public void afterAppointmentsItemChange(List<AppointmentsItems> items) {

        items.stream()
            .map(AppointmentsItems::getParentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet())
            .forEach(this::calculateAppointmentTotalSum);
    }

    @After(event = {DraftService.EVENT_DRAFT_NEW, DraftService.EVENT_DRAFT_PATCH},
        entity = AppointmentsItems_.CDS_NAME)
    public void afterAppointmentsItemDraftChange(EventContext context, List<AppointmentsItems> items) {

        items.stream()
            .map(AppointmentsItems::getParentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet())
            .forEach(appointmentId -> calculateAppointmentDraftTotalSum(context, appointmentId));
    }

    @After(event = {CqnService.EVENT_CREATE, CqnService.EVENT_UPDATE}, entity = Appointments_.CDS_NAME)
    public void afterDeepAppointmentChange(List<Appointments> appointments) {
        appointments.stream()
                .filter(app -> app.getItems() != null)
                .map(Appointments::getId)
                .filter(Objects::nonNull)
                .forEach(this::calculateAppointmentTotalSum);
    }

    @Before(event = CqnService.EVENT_DELETE, entity = AppointmentsItems_.CDS_NAME)
    public void beforeAppointmentsItemDelete(EventContext context, CqnDelete delete) {
        var selectRows = Select.from(delete.ref());
        delete.where().ifPresent(selectRows::where);

        var itemsToBeDeleted = db.run(selectRows).listOf(AppointmentsItems.class);

        if (!itemsToBeDeleted.isEmpty()) {
            context.put(DELETED_ITEMS, itemsToBeDeleted);
        }
    }

    @After(event = CqnService.EVENT_DELETE, entity = AppointmentsItems_.CDS_NAME)
    public void afterAppointmentsItemDelete(EventContext context) {
        @SuppressWarnings("unchecked")
        var deletedItems = (List<AppointmentsItems>) context.get(DELETED_ITEMS);

        if (deletedItems != null && !deletedItems.isEmpty()){
            deletedItems.stream()
                        .map(AppointmentsItems::getParentId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet())
                        .forEach(this::calculateAppointmentTotalSum);
        }
    }

    private void calculateAppointmentTotalSum(String appointmentId) {
        CqnSelect selectItems = Select.from(AppointmentsItems_.class)
                 .where(i -> i.parent_ID().eq(appointmentId));

        var appointmentItems = db.run(selectItems).listOf(AppointmentsItems.class);

        BigDecimal totalSum = sumOf(appointmentItems);

        db.run(Update.entity(Appointments_.class)
                 .data(Appointments.TOTAL_AMOUNT, totalSum)
                 .where(a -> a.ID().eq(appointmentId)));
    }

    private void calculateAppointmentDraftTotalSum(EventContext context, String appointmentId) {
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            var draftItems = draftService.run(Select.from(AppointmentsItems_.class)
                            .where(i -> i.parent_ID().eq(appointmentId)
                                    .and(i.IsActiveEntity().eq(false))))
                    .listOf(AppointmentsItems.class);

            BigDecimal totalSum = sumOf(draftItems);

            draftService.patchDraft(Update.entity(Appointments_.class)
                    .data(Appointments.TOTAL_AMOUNT, totalSum)
                    .where(a -> a.ID().eq(appointmentId).and(a.IsActiveEntity().eq(false))));
        });
    }

    private static BigDecimal sumOf(List<AppointmentsItems> items) {
        return items.stream()
                .map(ItemPricingHandler::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
