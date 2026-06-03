package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
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
import cds.gen.com.sap.autorepair.ItemType;
import com.sap.cds.ql.cqn.AnalysisResult;
import com.sap.cds.ql.cqn.CqnAnalyzer;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class StandardMaintenanceHandler implements EventHandler {

        static final String STD_OIL_ARTICLE = "STD-OIL-001";
        static final String STD_FILTER_ARTICLE = "STD-FILTER-001";
        static final String STD_OIL_CHANGE_CODE = "STD-MAINT-001";

        private static final BigDecimal OIL_DEFAULT_QUANTITY = new BigDecimal("4");
        private static final BigDecimal FILTER_DEFAULT_QUANTITY = BigDecimal.ONE;
        private static final int WORK_POSITION = 10;
        private static final int OIL_POSITION = 20;
        private static final int FILTER_POSITION = 30;
        private static final String OPEN_IN_EDIT_MODE_MESSAGE = "Please open the appointment in Edit mode first, then apply Standard Maintenance.";

        private final PersistenceService db;

        public StandardMaintenanceHandler(PersistenceService db) {
                this.db = db;
        }

        @On(event = AppointmentsApplyStandardMaintenanceContext.CDS_NAME, entity = {
                        Appointments_.CDS_NAME
        })
        public void onApplyStandardMaintenance(AppointmentsApplyStandardMaintenanceContext context) {
                handleApplyStandardMaintenance(context);
        }

        private void handleApplyStandardMaintenance(AppointmentsApplyStandardMaintenanceContext context) {
                AnalysisResult analysis = CqnAnalyzer.create(context.getModel()).analyze(context.getCqn());
                String appointmentId = extractAppointmentId(analysis);
                if (isActiveRequest(analysis)) {
                        throw new ServiceException(ErrorStatuses.BAD_REQUEST, OPEN_IN_EDIT_MODE_MESSAGE);
                }

                Appointments appointment = loadDraftAppointment(context, appointmentId);

                Map<String, Stocks> standardParts = loadStandardParts();
                ServicesOffered standardService = loadStandardService();

                List<AppointmentsItems> newItems = List.of(
                                createWorkItem(appointment, appointmentId, WORK_POSITION, standardService),
                                createPartItem(appointment, appointmentId, OIL_POSITION,
                                                requirePart(standardParts, STD_OIL_ARTICLE), OIL_DEFAULT_QUANTITY),
                                createPartItem(appointment, appointmentId, FILTER_POSITION,
                                                requirePart(standardParts, STD_FILTER_ARTICLE),
                                                FILTER_DEFAULT_QUANTITY));

                List<AppointmentsItems> mergedItems = new ArrayList<>(loadDraftItems(context, appointmentId));
                mergedItems.addAll(newItems);

                Map<String, Object> draftPatch = Map.of(
                                Appointments.ID, appointmentId,
                                Appointments.IS_ACTIVE_ENTITY, false,
                                Appointments.ITEMS, mergedItems);

                context.getService().run(
                                Update.entity(Appointments_.class)
                                                .data(draftPatch)
                                                .where(a -> a.ID().eq(appointmentId)
                                                                .and(a.IsActiveEntity().eq(false))));

                context.setResult(loadDraftAppointment(context, appointmentId));
                context.setCompleted();
        }

        private Appointments loadDraftAppointment(AppointmentsApplyStandardMaintenanceContext context,
                        String appointmentId) {
                return context.getService().run(
                                Select.from(Appointments_.class)
                                                .where(a -> a.ID().eq(appointmentId).and(a.IsActiveEntity().eq(false))))
                                .single(Appointments.class);
        }

        private List<AppointmentsItems> loadDraftItems(AppointmentsApplyStandardMaintenanceContext context,
                        String appointmentId) {
                return context.getService().run(
                                Select.from(AppointmentsItems_.class)
                                                .where(i -> i.parent_ID().eq(appointmentId)
                                                                .and(i.IsActiveEntity().eq(false))))
                                .listOf(AppointmentsItems.class);
        }

        private Map<String, Stocks> loadStandardParts() {
                return db.run(
                                Select.from(Stocks_.class)
                                                .where(s -> s.articleNo()
                                                                .in(List.of(STD_OIL_ARTICLE, STD_FILTER_ARTICLE))))
                                .listOf(Stocks.class)
                                .stream()
                                .collect(Collectors.toMap(Stocks::getArticleNo, stock -> stock));
        }

        private ServicesOffered loadStandardService() {
                return db.run(
                                Select.from(ServicesOffered_.class)
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

        private AppointmentsItems createWorkItem(Appointments appointment, String appointmentId, int position,
                        ServicesOffered standardService) {
                var workItem = AppointmentsItems.create();
                workItem.setParentId(appointmentId);
                workItem.setPos(position);
                workItem.setType(ItemType.WORK);
                workItem.setDescription(standardService.getName());
                workItem.setDuration(formatDuration(standardService.getStandardHour()));
                workItem.setPrice(standardService.getPrice());
                workItem.setCurrencyCode(standardService.getCurrencyCode());
                workItem.setServicesOfferedItemId(standardService.getId());
                applyDraftMetadata(workItem, appointment);
                return workItem;
        }

        private AppointmentsItems createPartItem(Appointments appointment, String appointmentId, int position,
                        Stocks part,
                        BigDecimal quantity) {
                var partItem = AppointmentsItems.create();
                partItem.setParentId(appointmentId);
                partItem.setPos(position);
                partItem.setType(ItemType.PART);
                partItem.setDescription(part.getName());
                partItem.setQuantity(quantity);
                partItem.setPrice(part.getPrice());
                partItem.setCurrencyCode(part.getCurrencyCode());
                partItem.setStockItemId(part.getId());
                applyDraftMetadata(partItem, appointment);
                return partItem;
        }

        private void applyDraftMetadata(AppointmentsItems item, Appointments appointment) {
                item.setIsActiveEntity(false);
                item.setDraftAdministrativeDataDraftUUID(appointment.getDraftAdministrativeDataDraftUUID());
        }

        private String formatDuration(BigDecimal standardHour) {
                if (standardHour == null) {
                        return null;
                }
                return standardHour.stripTrailingZeros().toPlainString() + "h";
        }

        private String extractAppointmentId(AnalysisResult analysis) {
                Object idValue = analysis.targetKeys().get(Appointments.ID);
                if (idValue == null) {
                        throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                                        "Unable to determine appointment ID for Standard Maintenance action.");
                }
                return String.valueOf(idValue);
        }

        private boolean isActiveRequest(AnalysisResult analysis) {
                Object activeFlag = analysis.targetKeys().get(Appointments.IS_ACTIVE_ENTITY);
                return Boolean.TRUE.equals(activeFlag);
        }
}
