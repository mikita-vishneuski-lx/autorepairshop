package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.AnalysisResult;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsApplyStandardMaintenanceContext;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.Appointments_;
import cds.gen.repairservice.RepairService_;
import cds.gen.repairservice.ServicesOffered;
import cds.gen.repairservice.ServicesOffered_;
import cds.gen.repairservice.Stocks;
import cds.gen.repairservice.Stocks_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class StandardMaintenanceHandler implements EventHandler {

    static final String STD_OIL_ARTICLE = "STD-OIL-001";
    static final String STD_FILTER_ARTICLE = "STD-FILTER-001";
    static final String STD_OIL_CHANGE_CODE = "STD-MAINT-001";

    private static final BigDecimal OIL_DEFAULT_QUANTITY = new BigDecimal("4");
    private static final BigDecimal FILTER_DEFAULT_QUANTITY = BigDecimal.ONE;
    private static final String OPEN_IN_EDIT_MODE_MESSAGE =
            "Please open the appointment in Edit mode first, then apply Standard Maintenance.";

    private final PersistenceService db;
    private final DraftService draftService;

    public StandardMaintenanceHandler(PersistenceService db,
            @Qualifier(RepairService_.CDS_NAME) DraftService draftService) {
        this.db = db;
        this.draftService = draftService;
    }

    @On(event = AppointmentsApplyStandardMaintenanceContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onApplyStandardMaintenance(AppointmentsApplyStandardMaintenanceContext context) {
        if (!context.getUserInfo().hasRole("Mechanic")) {
            throw new ServiceException(ErrorStatuses.FORBIDDEN,
                    "Only Mechanic may apply Standard Maintenance.");
        }
        AnalysisResult analysis = CqnAnalyzer.create(context.getModel()).analyze(context.getCqn());
        if (Boolean.TRUE.equals(analysis.targetKeys().get(Appointments.IS_ACTIVE_ENTITY))) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, OPEN_IN_EDIT_MODE_MESSAGE);
        }
        Object idValue = analysis.targetKeys().get(Appointments.ID);
        if (idValue == null) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "Unable to determine appointment ID for Standard Maintenance action.");
        }
        String appointmentId = String.valueOf(idValue);
        Appointments appointment = loadDraftAppointment(appointmentId);

        if (!"Inspection".equals(appointment.getStatus())) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "Standard Maintenance can only be applied while the appointment is under inspection.");
        }

        Map<String, Stocks> standardParts = loadStandardParts();
        ServicesOffered standardService = loadStandardService();

        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            addOrIncrementWork(appointmentId, standardService.getId(), BigDecimal.ONE);
            addOrIncrementPart(appointmentId, requirePart(standardParts, STD_OIL_ARTICLE).getId(),
                    OIL_DEFAULT_QUANTITY);
            addOrIncrementPart(appointmentId, requirePart(standardParts, STD_FILTER_ARTICLE).getId(),
                    FILTER_DEFAULT_QUANTITY);
        });

        context.setResult(loadDraftAppointment(appointmentId));
        context.setCompleted();
    }

    private void addOrIncrementWork(String appointmentId, String serviceId, BigDecimal additionalHours) {
        for (AppointmentsItems existing : loadDraftItems(appointmentId)) {
            if (serviceId.equals(existing.getServicesOfferedItemId()) && existing.getPos() != null) {
                BigDecimal current = existing.getDuration() != null ? existing.getDuration() : BigDecimal.ZERO;
                patchItem(appointmentId, existing.getPos(), AppointmentsItems.DURATION, current.add(additionalHours));
                return;
            }
        }
        AppointmentsItems item = AppointmentsItems.create();
        item.setParentId(appointmentId);
        item.setServicesOfferedItemId(serviceId);
        item.setDuration(additionalHours);
        draftService.newDraft(Insert.into(AppointmentsItems_.class).entry(item));
    }

    private void addOrIncrementPart(String appointmentId, String stockId, BigDecimal additionalQuantity) {
        for (AppointmentsItems existing : loadDraftItems(appointmentId)) {
            if (stockId.equals(existing.getStockItemId()) && existing.getPos() != null) {
                BigDecimal current = existing.getQuantity() != null ? existing.getQuantity() : BigDecimal.ZERO;
                patchItem(appointmentId, existing.getPos(), AppointmentsItems.QUANTITY, current.add(additionalQuantity));
                return;
            }
        }
        AppointmentsItems item = AppointmentsItems.create();
        item.setParentId(appointmentId);
        item.setStockItemId(stockId);
        item.setQuantity(additionalQuantity);
        draftService.newDraft(Insert.into(AppointmentsItems_.class).entry(item));
    }

    private void patchItem(String appointmentId, int pos, String field, BigDecimal value) {
        draftService.patchDraft(Update.entity(AppointmentsItems_.class)
                .data(field, value)
                .where(i -> i.parent_ID().eq(appointmentId)
                        .and(i.pos().eq(pos))
                        .and(i.IsActiveEntity().eq(false))));
    }

    private Appointments loadDraftAppointment(String appointmentId) {
        return draftService.run(Select.from(Appointments_.class)
                        .where(a -> a.ID().eq(appointmentId).and(a.IsActiveEntity().eq(false))))
                .single(Appointments.class);
    }

    private List<AppointmentsItems> loadDraftItems(String appointmentId) {
        return draftService.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(appointmentId).and(i.IsActiveEntity().eq(false))))
                .listOf(AppointmentsItems.class);
    }

    private Map<String, Stocks> loadStandardParts() {
        return db.run(Select.from(Stocks_.class)
                        .where(s -> s.articleNo().in(List.of(STD_OIL_ARTICLE, STD_FILTER_ARTICLE))))
                .listOf(Stocks.class).stream()
                .collect(Collectors.toMap(Stocks::getArticleNo, s -> s));
    }

    private ServicesOffered loadStandardService() {
        return db.run(Select.from(ServicesOffered_.class)
                        .where(s -> s.workCode().eq(STD_OIL_CHANGE_CODE)))
                .first(ServicesOffered.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Standard maintenance service not found: " + STD_OIL_CHANGE_CODE));
    }

    private Stocks requirePart(Map<String, Stocks> standardParts, String articleNo) {
        Stocks part = standardParts.get(articleNo);
        if (part == null) {
            throw new ServiceException(ErrorStatuses.NOT_FOUND,
                    "Standard maintenance part not found: " + articleNo);
        }
        return part;
    }
}
