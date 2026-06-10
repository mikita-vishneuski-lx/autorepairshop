package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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

    private final PersistenceService db;
    private final DraftService draftService;

    public StandardMaintenanceHandler(PersistenceService db,
            @Qualifier(RepairService_.CDS_NAME) DraftService draftService) {
        this.db = db;
        this.draftService = draftService;
    }

    @On(event = AppointmentsApplyStandardMaintenanceContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onApplyStandardMaintenance(AppointmentsApplyStandardMaintenanceContext context) {
        if (!context.getUserInfo().hasRole(AppointmentSecurityHandler.ROLE_MECHANIC)) {
            throw new ServiceException(ErrorStatuses.FORBIDDEN, "Only Mechanic may apply Standard Maintenance.");
        }
        AnalysisResult analysis = CqnAnalyzer.create(context.getModel()).analyze(context.getCqn());
        if (Boolean.TRUE.equals(analysis.targetKeys().get(Appointments.IS_ACTIVE_ENTITY))) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "Please open the appointment in Edit mode first, then apply Standard Maintenance.");
        }
        Object idValue = analysis.targetKeys().get(Appointments.ID);
        if (idValue == null) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "Unable to determine appointment ID for Standard Maintenance action.");
        }
        String appointmentId = String.valueOf(idValue);
        Appointments appointment = loadDraftAppointment(appointmentId);
        if (!AppointmentHandler.STATUS_INSPECTION.equals(appointment.getStatus())) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "Standard Maintenance can only be applied while the appointment is under inspection.");
        }

        Map<String, Stocks> standardParts = loadStandardParts();
        ServicesOffered standardService = loadStandardService();

        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            addOrIncrement(appointmentId, standardService.getId(), null, BigDecimal.ONE);
            addOrIncrement(appointmentId, null, requirePart(standardParts, STD_OIL_ARTICLE).getId(), OIL_DEFAULT_QUANTITY);
            addOrIncrement(appointmentId, null, requirePart(standardParts, STD_FILTER_ARTICLE).getId(), FILTER_DEFAULT_QUANTITY);
        });

        context.setResult(loadDraftAppointment(appointmentId));
        context.setCompleted();
    }

    private void addOrIncrement(String appointmentId, String serviceId, String stockId, BigDecimal delta) {
        boolean isWork = serviceId != null;
        String matchAgainst = isWork ? serviceId : stockId;
        String field = isWork ? AppointmentsItems.DURATION : AppointmentsItems.QUANTITY;
        for (AppointmentsItems existing : loadDraftItems(appointmentId)) {
            if (existing.getPos() == null) {
                continue;
            }
            String existingRef = isWork ? existing.getServicesOfferedItemId() : existing.getStockItemId();
            boolean match = Objects.equals(matchAgainst, existingRef);
            if (!match) {
                continue;
            }
            BigDecimal current = isWork
                    ? (existing.getDuration() != null ? existing.getDuration() : BigDecimal.ZERO)
                    : (existing.getQuantity() != null ? existing.getQuantity() : BigDecimal.ZERO);
            draftService.patchDraft(Update.entity(AppointmentsItems_.class)
                    .data(field, current.add(delta))
                    .where(i -> i.parent_ID().eq(appointmentId)
                            .and(i.pos().eq(existing.getPos()))
                            .and(i.IsActiveEntity().eq(false))));
            return;
        }
        AppointmentsItems item = AppointmentsItems.create();
        item.setParentId(appointmentId);
        if (isWork) {
            item.setServicesOfferedItemId(serviceId);
            item.setDuration(delta);
        } else {
            item.setStockItemId(stockId);
            item.setQuantity(delta);
        }
        draftService.newDraft(Insert.into(AppointmentsItems_.class).entry(item));
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

    private static Stocks requirePart(Map<String, Stocks> standardParts, String articleNo) {
        Stocks part = standardParts.get(articleNo);
        if (part == null) {
            throw new ServiceException(ErrorStatuses.NOT_FOUND,
                    "Standard maintenance part not found: " + articleNo);
        }
        return part;
    }
}
