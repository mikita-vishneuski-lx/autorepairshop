package customer.autorepairshop.handlers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import cds.gen.com.sap.autorepair.ItemType;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.AnalysisResult;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import org.springframework.stereotype.Component;

import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.messages.Messages;
import com.sap.cds.services.persistence.PersistenceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsAddPartContext;
import cds.gen.repairservice.AppointmentsAddWorkContext;
import cds.gen.repairservice.AppointmentsCancelContext;
import cds.gen.repairservice.AppointmentsCloseContext;
import cds.gen.repairservice.AppointmentsCompleteContext;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.AppointmentsItemsApproveItemContext;
import cds.gen.repairservice.AppointmentsItemsRejectItemContext;
import cds.gen.repairservice.AppointmentsRequestApprovalContext;
import cds.gen.repairservice.AppointmentsStartInspectionContext;
import cds.gen.repairservice.Appointments_;
import cds.gen.repairservice.MasterLogs;
import cds.gen.repairservice.MasterLogs_;
import cds.gen.repairservice.RepairService_;
import cds.gen.repairservice.Stocks;
import cds.gen.repairservice.Stocks_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class AppointmentHandler implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentHandler.class);

    private final PersistenceService db;
    private final Messages messages;
    private final DraftService draftService;

    public AppointmentHandler(Messages messages, PersistenceService db,
                              @Qualifier(RepairService_.CDS_NAME) DraftService draftService) {
        this.db = db;
        this.messages = messages;
        this.draftService = draftService;
    }

    @Before(event = {CqnService.EVENT_CREATE, CqnService.EVENT_UPDATE}, entity = Appointments_.CDS_NAME)
    public void validateStockOnParent(List<Appointments> appointments) {

        if(appointments == null || appointments.isEmpty()) {
            return;
        }

        var allItems = new ArrayList<AppointmentsItems>();

        for (Appointments appointment : appointments) {
            if (appointment.getItems() != null && !appointment.getItems().isEmpty()) {
                allItems.addAll(appointment.getItems());
            }
        }

        if (!allItems.isEmpty()) {
            performStockValidation(allItems);
        }
    }

    @Before(event = {CqnService.EVENT_CREATE, CqnService.EVENT_UPDATE}, entity = AppointmentsItems_.CDS_NAME)
    public void validateStockOnChild(List<AppointmentsItems> appointmentsItems) {
        if(appointmentsItems != null && !appointmentsItems.isEmpty()) {
            performStockValidation(appointmentsItems);
        }
    }

    @On(event = AppointmentsStartInspectionContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onStartInspection(AppointmentsStartInspectionContext context) {
        requireAnyRole(context, "Manager");
        boolean isActive = extractIsActive(context.getCqn(), context.getModel());
        requireDraft(isActive);
        String id = extractAppointmentId(context.getCqn(), context.getModel());
        requireCurrentStatus(id, isActive, "Created",
                "Inspection can only be started for newly created appointments");
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            updateAppointmentStatus(id, isActive, "Inspection");
        });
        context.setResult(loadAppointment(id, isActive));
        context.setCompleted();
    }

    @On(event = AppointmentsRequestApprovalContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onRequestApproval(AppointmentsRequestApprovalContext context) {
        requireAnyRole(context, "Mechanic");
        boolean isActive = extractIsActive(context.getCqn(), context.getModel());
        requireDraft(isActive);
        String id = extractAppointmentId(context.getCqn(), context.getModel());
        requireCurrentStatus(id, isActive, "Inspection",
                "Approval can only be requested while the appointment is under inspection");
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            updateAppointmentStatus(id, isActive, "Waiting for approval");
        });
        context.setResult(loadAppointment(id, isActive));
        context.setCompleted();
    }

    @On(event = AppointmentsAddPartContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onAddPart(AppointmentsAddPartContext context) {
        String stockId = context.getStockItem();
        if (stockId == null || stockId.isBlank()) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "Please pick a part.");
        }
        addItemToDraft(context, stockId, null);
        boolean isActive = extractIsActive(context.getCqn(), context.getModel());
        String id = extractAppointmentId(context.getCqn(), context.getModel());
        context.setResult(loadAppointment(id, isActive));
        context.setCompleted();
    }

    @On(event = AppointmentsAddWorkContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onAddWork(AppointmentsAddWorkContext context) {
        String serviceId = context.getServicesOfferedItem();
        if (serviceId == null || serviceId.isBlank()) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "Please pick a service.");
        }
        addItemToDraft(context, null, serviceId);
        boolean isActive = extractIsActive(context.getCqn(), context.getModel());
        String id = extractAppointmentId(context.getCqn(), context.getModel());
        context.setResult(loadAppointment(id, isActive));
        context.setCompleted();
    }

    private void addItemToDraft(com.sap.cds.services.EventContext context, String stockId, String serviceId) {
        requireAnyRole(context, "Mechanic");
        com.sap.cds.ql.cqn.CqnSelect cqn = (com.sap.cds.ql.cqn.CqnSelect) context.get("cqn");
        boolean isActive = extractIsActive(cqn, context.getModel());
        requireDraft(isActive);
        String parentId = extractAppointmentId(cqn, context.getModel());
        String status = loadCurrentStatusOn(parentId, isActive);
        if (!"Inspection".equals(status) && !"In Progress".equals(status)) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "Items can only be added while the appointment is under inspection or in progress");
        }
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            if (incrementExistingDraftItem(parentId, stockId, serviceId)) {
                return;
            }
            int nextPos = nextPosFor(parentId);
            AppointmentsItems item = AppointmentsItems.create();
            item.setParentId(parentId);
            item.setPos(nextPos);
            if (stockId != null) {
                item.setStockItemId(stockId);
            }
            if (serviceId != null) {
                item.setServicesOfferedItemId(serviceId);
            }
            draftService.newDraft(Insert.into(AppointmentsItems_.class).entry(item));
        });
    }

    private boolean incrementExistingDraftItem(String parentId, String stockId, String serviceId) {
        var draftItems = draftService.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(parentId).and(i.IsActiveEntity().eq(false))))
                .listOf(AppointmentsItems.class);
        for (AppointmentsItems existing : draftItems) {
            boolean match = (stockId != null && stockId.equals(existing.getStockItemId()))
                    || (serviceId != null && serviceId.equals(existing.getServicesOfferedItemId()));
            if (!match || existing.getPos() == null) {
                continue;
            }
            final int pos = existing.getPos();
            AppointmentsItems patch = AppointmentsItems.create();
            if (stockId != null) {
                java.math.BigDecimal current = existing.getQuantity() != null
                        ? existing.getQuantity() : java.math.BigDecimal.ZERO;
                patch.setQuantity(current.add(java.math.BigDecimal.ONE));
            } else {
                java.math.BigDecimal current = existing.getDuration() != null
                        ? existing.getDuration() : java.math.BigDecimal.ZERO;
                patch.setDuration(current.add(java.math.BigDecimal.ONE));
            }
            draftService.patchDraft(Update.entity(AppointmentsItems_.class)
                    .data(patch)
                    .where(i -> i.parent_ID().eq(parentId)
                            .and(i.pos().eq(pos))
                            .and(i.IsActiveEntity().eq(false))));
            return true;
        }
        return false;
    }

    @On(event = AppointmentsItemsApproveItemContext.CDS_NAME, entity = AppointmentsItems_.CDS_NAME)
    public void onApproveItem(AppointmentsItemsApproveItemContext context) {
        requireAnyRole(context, "Client");
        ItemKey key = extractItemKey(context.getCqn(), context.getModel());
        requireDraft(key.isActive());
        AppointmentsItems item = loadItem(key);
        ensureClientOwnership(context, item.getParentId());
        requireCurrentStatus(item.getParentId(), key.isActive(), "Waiting for approval",
                "Items can only be decided while the appointment is waiting for approval");
        requireItemStatus(item, "Proposed");
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            updateItemStatus(key, "Approved");
            recomputeAppointmentStatus(item.getParentId(), key.isActive());
        });
        context.setResult(reloadItem(key));
        context.setCompleted();
    }

    @On(event = AppointmentsItemsRejectItemContext.CDS_NAME, entity = AppointmentsItems_.CDS_NAME)
    public void onRejectItem(AppointmentsItemsRejectItemContext context) {
        requireAnyRole(context, "Client");
        ItemKey key = extractItemKey(context.getCqn(), context.getModel());
        requireDraft(key.isActive());
        AppointmentsItems item = loadItem(key);
        ensureClientOwnership(context, item.getParentId());
        requireCurrentStatus(item.getParentId(), key.isActive(), "Waiting for approval",
                "Items can only be decided while the appointment is waiting for approval");
        requireItemStatus(item, "Proposed");
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            updateItemStatus(key, "Rejected");
            recomputeAppointmentStatus(item.getParentId(), key.isActive());
        });
        context.setResult(reloadItem(key));
        context.setCompleted();
    }

    @On(event = AppointmentsCompleteContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onComplete(AppointmentsCompleteContext context) {
        requireAnyRole(context, "Mechanic");
        boolean isActive = extractIsActive(context.getCqn(), context.getModel());
        requireDraft(isActive);
        String id = extractAppointmentId(context.getCqn(), context.getModel());
        requireCurrentStatus(id, isActive, "In Progress",
                "Only in-progress appointments can be marked as completed");
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            updateAppointmentStatus(id, isActive, "Completed");
            createWorkItemMasterLogs(id, isActive);
        });
        context.setResult(loadAppointment(id, isActive));
        context.setCompleted();
    }

    @On(event = AppointmentsCloseContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onClose(AppointmentsCloseContext context) {
        requireAnyRole(context, "Mechanic");
        boolean isActive = extractIsActive(context.getCqn(), context.getModel());
        requireDraft(isActive);
        String id = extractAppointmentId(context.getCqn(), context.getModel());
        requireCurrentStatus(id, isActive, "Completed",
                "Only completed appointments can be closed");
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            updateAppointmentStatus(id, isActive, "Closed");
        });
        context.setResult(loadAppointment(id, isActive));
        context.setCompleted();
    }

    @On(event = AppointmentsCancelContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onCancel(AppointmentsCancelContext context) {
        requireAnyRole(context, "Client", "Manager");
        boolean isActive = extractIsActive(context.getCqn(), context.getModel());
        requireDraft(isActive);
        String id = extractAppointmentId(context.getCqn(), context.getModel());
        ensureClientOwnership(context, id);
        Set<String> cancellable = Set.of("Created", "Inspection", "Waiting for approval");
        String current = loadCurrentStatusOn(id, isActive);
        if (!cancellable.contains(current)) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "Appointment cannot be cancelled once work has started or finished");
        }
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            updateAppointmentStatus(id, isActive, "Cancelled");
        });
        context.setResult(loadAppointment(id, isActive));
        context.setCompleted();
    }

    @com.sap.cds.services.handler.annotations.Before(event = DraftService.EVENT_DRAFT_SAVE, entity = Appointments_.CDS_NAME)
    public void onBeforeDraftSave(com.sap.cds.services.draft.DraftSaveEventContext context) {
        String id;
        try {
            id = extractAppointmentId(context.getCqn(), context.getModel());
        } catch (Exception ex) {
            log.warn("DRAFT_SAVE: could not extract appointment id: {}", ex.getMessage());
            return;
        }
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            String draftStatus = draftService.run(Select.from(Appointments_.class)
                            .columns(a -> a.status())
                            .where(a -> a.ID().eq(id).and(a.IsActiveEntity().eq(false))))
                    .first(Appointments.class)
                    .map(Appointments::getStatus)
                    .orElse(null);
            if (draftStatus != null) {
                db.run(Update.entity(Appointments_.class)
                        .data(Appointments.STATUS, draftStatus)
                        .where(a -> a.ID().eq(id).and(a.IsActiveEntity().eq(true))));
            }
            var items = draftService.run(Select.from(AppointmentsItems_.class)
                            .where(i -> i.parent_ID().eq(id).and(i.IsActiveEntity().eq(false))))
                    .listOf(AppointmentsItems.class);
            for (AppointmentsItems it : items) {
                if (it.getItemStatus() == null || it.getPos() == null) {
                    continue;
                }
                db.run(Update.entity(AppointmentsItems_.class)
                        .data(AppointmentsItems.ITEM_STATUS, it.getItemStatus())
                        .where(a -> a.parent_ID().eq(id)
                                .and(a.pos().eq(it.getPos()))
                                .and(a.IsActiveEntity().eq(true))));
            }
        });
    }

    @com.sap.cds.services.handler.annotations.Before(event = DraftService.EVENT_DRAFT_NEW, entity = AppointmentsItems_.CDS_NAME)
    public void assignItemPos(com.sap.cds.services.EventContext context, List<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        String contextParentId = resolveParentIdFromContext(context);
        for (AppointmentsItems item : items) {
            if (Boolean.TRUE.equals(item.getHasActiveEntity())) {
                continue;
            }
            if (item.getPos() != null && item.getPos() != 0) {
                continue;
            }
            String parentId = item.getParentId() != null ? item.getParentId() : contextParentId;
            if (parentId == null) {
                continue;
            }
            int nextPos = nextPosFor(parentId);
            item.setPos(nextPos);
            if (item.getParentId() == null) {
                item.setParentId(parentId);
            }
        }
    }

    private int nextPosFor(String parentId) {
        int maxActive = db.run(Select.from(AppointmentsItems_.class)
                        .columns(i -> i.pos())
                        .where(i -> i.parent_ID().eq(parentId)))
                .listOf(AppointmentsItems.class).stream()
                .map(AppointmentsItems::getPos)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        int maxDraft = draftService.run(Select.from(AppointmentsItems_.class)
                        .columns(i -> i.pos())
                        .where(i -> i.parent_ID().eq(parentId).and(i.IsActiveEntity().eq(false))))
                .listOf(AppointmentsItems.class).stream()
                .map(AppointmentsItems::getPos)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return Math.max(maxActive, maxDraft) + 10;
    }

    private static String resolveParentIdFromContext(com.sap.cds.services.EventContext context) {
        Object cqn = context.get("cqn");
        com.sap.cds.ql.cqn.CqnStructuredTypeRef ref = null;
        if (cqn instanceof com.sap.cds.ql.cqn.CqnInsert insert) {
            ref = insert.ref();
        } else if (cqn instanceof com.sap.cds.ql.cqn.CqnUpsert upsert) {
            ref = upsert.ref();
        }
        if (ref == null) {
            return null;
        }
        AnalysisResult analysis = CqnAnalyzer.create(context.getModel()).analyze(ref);
        Object id = analysis.targetKeys().get("parent_ID");
        if (id == null) {
            id = analysis.rootKeys().get("ID");
        }
        return id == null ? null : id.toString();
    }

    @com.sap.cds.services.handler.annotations.After(event = DraftService.EVENT_DRAFT_NEW, entity = AppointmentsItems_.CDS_NAME)
    public void onItemDraftNew(com.sap.cds.services.EventContext context, List<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<String> parentIds = items.stream()
                .map(AppointmentsItems::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (parentIds.isEmpty()) {
            return;
        }
        for (String parentId : parentIds) {
            String parentStatus = draftService.run(Select.from(Appointments_.class)
                            .columns(a -> a.status())
                            .where(a -> a.ID().eq(parentId).and(a.IsActiveEntity().eq(false))))
                    .first(Appointments.class)
                    .map(Appointments::getStatus)
                    .orElse(null);
            if ("In Progress".equals(parentStatus)) {
                final String pid = parentId;
                context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
                    draftService.patchDraft(Update.entity(Appointments_.class)
                            .data(Appointments.STATUS, "Waiting for approval")
                            .where(a -> a.ID().eq(pid).and(a.IsActiveEntity().eq(false))));
                });
            }
        }
    }

    private void requireAnyRole(com.sap.cds.services.EventContext context, String... roles) {
        com.sap.cds.services.request.UserInfo user = context.getUserInfo();
        for (String role : roles) {
            if (user.hasRole(role)) {
                return;
            }
        }
        throw new ServiceException(ErrorStatuses.FORBIDDEN,
                "This action requires one of the roles: " + java.util.Arrays.toString(roles));
    }

    private void ensureClientOwnership(com.sap.cds.services.EventContext context, String id) {
        com.sap.cds.services.request.UserInfo user = context.getUserInfo();
        if (!user.hasRole("Client") || user.hasRole("Manager") || user.hasRole("Mechanic")) {
            return;
        }
        String owner = db.run(Select.from(Appointments_.class)
                        .columns(a -> a.createdBy())
                        .where(a -> a.ID().eq(id)))
                .first(Appointments.class)
                .map(Appointments::getCreatedBy)
                .orElse(null);
        if (owner == null || !owner.equals(user.getName())) {
            throw new ServiceException(ErrorStatuses.FORBIDDEN,
                    "Clients may only act on their own appointments");
        }
    }

    private String extractAppointmentId(com.sap.cds.ql.cqn.CqnSelect cqn, com.sap.cds.reflect.CdsModel model) {
        AnalysisResult analysis = CqnAnalyzer.create(model).analyze(cqn);
        Object idValue = analysis.targetKeys().get(Appointments.ID);
        if (idValue == null) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "Unable to determine appointment ID for status action");
        }
        return String.valueOf(idValue);
    }

    private boolean extractIsActive(com.sap.cds.ql.cqn.CqnSelect cqn, com.sap.cds.reflect.CdsModel model) {
        AnalysisResult analysis = CqnAnalyzer.create(model).analyze(cqn);
        Object v = analysis.targetKeys().get(Appointments.IS_ACTIVE_ENTITY);
        return v == null || Boolean.parseBoolean(String.valueOf(v));
    }

    private void requireDraft(boolean isActive) {
        if (isActive) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "This action can only be invoked on a draft. Please open the appointment in Edit mode first.");
        }
    }

    private record ItemKey(String parentId, Integer pos, boolean isActive) {}

    private ItemKey extractItemKey(com.sap.cds.ql.cqn.CqnSelect cqn, com.sap.cds.reflect.CdsModel model) {
        AnalysisResult analysis = CqnAnalyzer.create(model).analyze(cqn);
        Map<String, Object> keys = analysis.targetKeys();
        Object parentId = keys.get(AppointmentsItems.PARENT_ID);
        Object pos = keys.get(AppointmentsItems.POS);
        Object isActive = keys.get(AppointmentsItems.IS_ACTIVE_ENTITY);
        if (parentId == null || pos == null) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "Unable to determine item key");
        }
        return new ItemKey(
                String.valueOf(parentId),
                Integer.valueOf(String.valueOf(pos)),
                isActive == null || Boolean.parseBoolean(String.valueOf(isActive)));
    }

    private AppointmentsItems loadItem(ItemKey key) {
        var select = Select.from(AppointmentsItems_.class)
                .where(i -> i.parent_ID().eq(key.parentId())
                        .and(i.pos().eq(key.pos()))
                        .and(i.IsActiveEntity().eq(key.isActive())));
        var service = key.isActive() ? db : draftService;
        return service.run(select)
                .first(AppointmentsItems.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Item not found"));
    }

    private AppointmentsItems reloadItem(ItemKey key) {
        var select = Select.from(AppointmentsItems_.class)
                .where(i -> i.parent_ID().eq(key.parentId())
                        .and(i.pos().eq(key.pos()))
                        .and(i.IsActiveEntity().eq(key.isActive())));
        var service = key.isActive() ? db : draftService;
        return service.run(select).single(AppointmentsItems.class);
    }

    private void requireItemStatus(AppointmentsItems item, String expected) {
        if (!expected.equals(item.getItemStatus())) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "Item has already been decided (current: '" + item.getItemStatus() + "')");
        }
    }

    private void updateItemStatus(ItemKey key, String newStatus) {
        var update = Update.entity(AppointmentsItems_.class)
                .data(AppointmentsItems.ITEM_STATUS, newStatus)
                .where(i -> i.parent_ID().eq(key.parentId())
                        .and(i.pos().eq(key.pos()))
                        .and(i.IsActiveEntity().eq(key.isActive())));
        if (key.isActive()) {
            db.run(update);
        } else {
            draftService.patchDraft(update);
        }
    }

    private void recomputeAppointmentStatus(String appointmentId, boolean isActive) {
        var service = isActive ? db : draftService;
        var items = service.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(appointmentId)
                                .and(i.IsActiveEntity().eq(isActive))))
                .listOf(AppointmentsItems.class);
        if (items.isEmpty()) {
            return;
        }
        boolean anyProposed = items.stream().anyMatch(i -> "Proposed".equals(i.getItemStatus()));
        if (anyProposed) {
            return;
        }
        boolean anyApproved = items.stream().anyMatch(i -> "Approved".equals(i.getItemStatus()));
        updateAppointmentStatus(appointmentId, isActive, anyApproved ? "In Progress" : "Cancelled");
    }

    private void updateAppointmentStatus(String id, boolean isActive, String newStatus) {
        var update = Update.entity(Appointments_.class)
                .data(Appointments.STATUS, newStatus)
                .where(a -> a.ID().eq(id).and(a.IsActiveEntity().eq(isActive)));
        if (isActive) {
            db.run(update);
        } else {
            draftService.patchDraft(update);
        }
    }

    private void requireCurrentStatus(String id, boolean isActive, String expected, String message) {
        var service = isActive ? db : draftService;
        String current = service.run(Select.from(Appointments_.class)
                        .columns(a -> a.status())
                        .where(a -> a.ID().eq(id).and(a.IsActiveEntity().eq(isActive))))
                .first(Appointments.class)
                .map(Appointments::getStatus)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Appointment not found"));
        if (!expected.equals(current)) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, message);
        }
    }

    private String loadCurrentStatusOn(String id, boolean isActive) {
        var service = isActive ? db : draftService;
        return service.run(Select.from(Appointments_.class)
                        .columns(a -> a.status())
                        .where(a -> a.ID().eq(id).and(a.IsActiveEntity().eq(isActive))))
                .first(Appointments.class)
                .map(Appointments::getStatus)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Appointment not found: " + id));
    }

    private Appointments loadAppointment(String id, boolean isActive) {
        var service = isActive ? db : draftService;
        return service.run(Select.from(Appointments_.class)
                        .where(a -> a.ID().eq(id).and(a.IsActiveEntity().eq(isActive))))
                .single(Appointments.class);
    }

    private void createWorkItemMasterLogs(String appointmentId, boolean isActive) {
        var workItems = loadAppointmentItemsById(appointmentId, isActive).stream()
                .filter(item -> ItemType.WORK.equals(item.getType()))
                .toList();

        if (workItems.isEmpty()) {
            return;
        }

        var appointment = loadAppointment(appointmentId, isActive);
        var appointmentNo = appointment.getAppointmentNo();

        var logs = new ArrayList<MasterLogs>();
        for (var item : workItems) {
            var log = MasterLogs.create();
            log.setAppointmentId(appointmentId);
            log.setAppointmentNo(appointmentNo);
            log.setWorkDescription(item.getDescription());
            log.setDuration(item.getDuration());
            log.setPrice(item.getUnitPrice());
            log.setCurrencyCode(item.getCurrencyCode());
            log.setCreatedFromPos(item.getPos());
            logs.add(log);
        }

        db.run(Insert.into(MasterLogs_.class).entries(logs));
    }

    private void performStockValidation(List<AppointmentsItems> items) {
        validatePartsAgainstStock(items, false);
    }

    private List<AppointmentsItems> loadAppointmentItemsById(String appointmentId, boolean isActive) {
        if (appointmentId == null) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "Appointment ID is required");
        }

        var service = isActive ? db : draftService;
        return service.run(Select.from(AppointmentsItems_.class)
                .where(item -> item.parent_ID().eq(appointmentId)
                        .and(item.IsActiveEntity().eq(isActive))))
                .listOf(AppointmentsItems.class);
    }

    private void validatePartsAgainstStock(List<AppointmentsItems> items, boolean failOnInsufficientStock) {
        var stockItemsIds = new HashSet<String>();

        items.stream()
             .map(AppointmentsItems::getStockItemId)
             .filter(Objects::nonNull)
             .forEach(stockItemsIds::add);

        if (stockItemsIds.isEmpty()) {
            return;
        }

        List<Stocks> stocksFromDb = db.run(Select.from(Stocks_.class)
                                      .where(s -> s.ID().in(stockItemsIds)))
                                      .listOf(Stocks.class);

        Map<String, Stocks> stockMap = stocksFromDb.stream()
                .collect(Collectors.toMap(Stocks::getId, s -> s));

        for (AppointmentsItems item : items) {
            if (item.getStockItemId() == null || item.getQuantity() == null) {
                continue;
            }

            var stock = stockMap.get(item.getStockItemId());

            if (stock == null) {
                throw new ServiceException(ErrorStatuses.NOT_FOUND, "No part on stock! " + item.getStockItemId());
            }

            if (stock.getQuantity().compareTo(item.getQuantity()) < 0) {
                if (failOnInsufficientStock) {
                    throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                            "The request cannot be approved until the warehouse confirms critical spare parts availability");
                }

                messages.warn("There may be a delay. Not enough stock for: " + stock.getName());
            }
        }
    }
}
