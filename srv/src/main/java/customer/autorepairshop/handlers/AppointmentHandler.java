package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.AnalysisResult;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.ql.cqn.CqnInsert;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.ql.cqn.CqnStructuredTypeRef;
import com.sap.cds.ql.cqn.CqnUpsert;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.draft.DraftSaveEventContext;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.UserInfo;

import cds.gen.com.sap.autorepair.AppointmentItemStatusCode;
import cds.gen.com.sap.autorepair.AppointmentItemStatuses;
import cds.gen.com.sap.autorepair.AppointmentItemStatuses_;
import cds.gen.com.sap.autorepair.AppointmentStatusCode;
import cds.gen.com.sap.autorepair.ItemType;
import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsAddPartContext;
import cds.gen.repairservice.AppointmentsAddWorkContext;
import cds.gen.repairservice.AppointmentsApproveAllItemsContext;
import cds.gen.repairservice.AppointmentsCancelContext;
import cds.gen.repairservice.AppointmentsCloseContext;
import cds.gen.repairservice.AppointmentsCompleteContext;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.AppointmentsItemsApproveItemContext;
import cds.gen.repairservice.AppointmentsItemsRejectItemContext;
import cds.gen.repairservice.AppointmentsRejectAllItemsContext;
import cds.gen.repairservice.AppointmentsRequestApprovalContext;
import cds.gen.repairservice.AppointmentsStartInspectionContext;
import cds.gen.repairservice.Appointments_;
import cds.gen.repairservice.MasterLogs;
import cds.gen.repairservice.MasterLogs_;
import cds.gen.repairservice.RepairService_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class AppointmentHandler implements EventHandler {

    private final PersistenceService db;
    private final DraftService draftService;
    private final AppointmentWorkflowService workflowService;

    public AppointmentHandler(PersistenceService db,
                              @Qualifier(RepairService_.CDS_NAME) DraftService draftService,
                              AppointmentWorkflowService workflowService) {
        this.db = db;
        this.draftService = draftService;
        this.workflowService = workflowService;
    }

    @On(event = AppointmentsStartInspectionContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onStartInspection(AppointmentsStartInspectionContext context) {
        context.setResult(transition(context, context.getCqn(),
                AppointmentSecurityHandler.ROLE_MANAGER,
                AppointmentStatusCode.INSPECTION,
                "Inspection can only be started for newly created appointments",
                null));
        context.setCompleted();
    }

    @On(event = AppointmentsRequestApprovalContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onRequestApproval(AppointmentsRequestApprovalContext context) {
        context.setResult(transition(context, context.getCqn(),
                AppointmentSecurityHandler.ROLE_MECHANIC,
                AppointmentStatusCode.WAITING_FOR_APPROVAL,
                "Approval can only be requested while the appointment is under inspection",
                null));
        context.setCompleted();
    }

    @On(event = AppointmentsCompleteContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onComplete(AppointmentsCompleteContext context) {
        context.setResult(transition(context, context.getCqn(),
                AppointmentSecurityHandler.ROLE_MECHANIC,
                AppointmentStatusCode.COMPLETED,
                "Only in-progress appointments can be marked as completed",
                (id, isActive) -> createWorkItemMasterLogs(id, isActive)));
        context.setCompleted();
    }

    @On(event = AppointmentsCloseContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onClose(AppointmentsCloseContext context) {
        context.setResult(transition(context, context.getCqn(),
                AppointmentSecurityHandler.ROLE_MECHANIC,
                AppointmentStatusCode.CLOSED,
                "Only completed appointments can be closed",
                null));
        context.setCompleted();
    }

    @On(event = AppointmentsCancelContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onCancel(AppointmentsCancelContext context) {
        requireAnyRole(context,
                AppointmentSecurityHandler.ROLE_CLIENT,
                AppointmentSecurityHandler.ROLE_MANAGER);
        boolean isActive = extractIsActive(context.getCqn(), context.getModel());
        requireDraft(isActive);
        String id = extractAppointmentId(context.getCqn(), context.getModel());
        ensureClientOwnership(context, id);
        String current = loadStatus(id, isActive);
        workflowService.assertAppointmentTransition(current, AppointmentStatusCode.CANCELLED,
                "Appointment cannot be cancelled once work has started or finished");
        runPrivileged(context, () -> {
            updateAppointmentStatus(id, isActive, AppointmentStatusCode.CANCELLED);
            rejectOpenPartItemsInDraft(id);
        });
        context.setResult(loadAppointment(id, isActive));
        context.setCompleted();
    }

    @On(event = AppointmentsAddPartContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onAddPart(AppointmentsAddPartContext context) {
        String stockId = context.getStockItem();
        if (stockId == null || stockId.isBlank()) {
            throw badRequest("Please pick a part.");
        }
        context.setResult(addLineItem(context, stockId, null));
        context.setCompleted();
    }

    @On(event = AppointmentsAddWorkContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onAddWork(AppointmentsAddWorkContext context) {
        String serviceId = context.getServicesOfferedItem();
        if (serviceId == null || serviceId.isBlank()) {
            throw badRequest("Please pick a service.");
        }
        context.setResult(addLineItem(context, null, serviceId));
        context.setCompleted();
    }

    @On(event = AppointmentsItemsApproveItemContext.CDS_NAME, entity = AppointmentsItems_.CDS_NAME)
    public void onApproveItem(AppointmentsItemsApproveItemContext context) {
        context.setResult(decideItem(context, context.getCqn(), AppointmentItemStatusCode.APPROVED));
        context.setCompleted();
    }

    @On(event = AppointmentsItemsRejectItemContext.CDS_NAME, entity = AppointmentsItems_.CDS_NAME)
    public void onRejectItem(AppointmentsItemsRejectItemContext context) {
        context.setResult(decideItem(context, context.getCqn(), AppointmentItemStatusCode.REJECTED));
        context.setCompleted();
    }

    @On(event = AppointmentsApproveAllItemsContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onApproveAllItems(AppointmentsApproveAllItemsContext context) {
        context.setResult(decideAllItems(context, context.getCqn(), AppointmentItemStatusCode.APPROVED));
        context.setCompleted();
    }

    @On(event = AppointmentsRejectAllItemsContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onRejectAllItems(AppointmentsRejectAllItemsContext context) {
        context.setResult(decideAllItems(context, context.getCqn(), AppointmentItemStatusCode.REJECTED));
        context.setCompleted();
    }

    @Before(event = DraftService.EVENT_DRAFT_SAVE, entity = Appointments_.CDS_NAME)
    public void onBeforeDraftSave(DraftSaveEventContext context) {
        String id;
        try {
            id = extractAppointmentId(context.getCqn(), context.getModel());
        } catch (ServiceException ex) {
            return;
        }
        runPrivileged(context, () -> propagateDraftStatusToActive(id));
    }

    @Before(event = DraftService.EVENT_DRAFT_NEW, entity = AppointmentsItems_.CDS_NAME)
    public void assignItemPos(EventContext context, List<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        String contextParentId = parentIdFromContext(context);
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
            item.setPos(nextPosFor(parentId));
            if (item.getParentId() == null) {
                item.setParentId(parentId);
            }
        }
    }

    @After(event = DraftService.EVENT_DRAFT_NEW, entity = AppointmentsItems_.CDS_NAME)
    public void onItemDraftNew(EventContext context, List<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<String> parentIds = items.stream()
                .map(AppointmentsItems::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (String parentId : parentIds) {
            if (!AppointmentStatusCode.IN_PROGRESS.equals(loadDraftStatus(parentId))) {
                continue;
            }
            runPrivileged(context, () -> draftService.patchDraft(Update.entity(Appointments_.class)
                    .data(Appointments.STATUS_CODE, AppointmentStatusCode.WAITING_FOR_APPROVAL)
                    .where(a -> a.ID().eq(parentId).and(a.IsActiveEntity().eq(false)))));
        }
    }

    private Appointments transition(EventContext context, CqnSelect cqn,
                                    String requiredRole, String toStatus,
                                    String wrongStatusMsg, BiConsumer<String, Boolean> postAction) {
        requireAnyRole(context, requiredRole);
        boolean isActive = extractIsActive(cqn, context.getModel());
        requireDraft(isActive);
        String id = extractAppointmentId(cqn, context.getModel());
        String current = loadStatus(id, isActive);
        workflowService.assertAppointmentTransition(current, toStatus, wrongStatusMsg);
        runPrivileged(context, () -> {
            updateAppointmentStatus(id, isActive, toStatus);
            if (postAction != null) {
                postAction.accept(id, isActive);
            }
        });
        return loadAppointment(id, isActive);
    }

    private AppointmentsItems decideItem(EventContext context, CqnSelect cqn, String newStatus) {
        requireAnyRole(context, AppointmentSecurityHandler.ROLE_CLIENT);
        ItemKey key = extractItemKey(cqn, context.getModel());
        requireDraft(key.isActive());
        AppointmentsItems item = loadItem(key);
        ensureClientOwnership(context, item.getParentId());
        if (!AppointmentStatusCode.WAITING_FOR_APPROVAL.equals(loadStatus(item.getParentId(), key.isActive()))) {
            throw badRequest("Items can only be decided while the appointment is waiting for approval");
        }
        workflowService.assertItemTransition(item.getItemStatusCode(), newStatus,
                "Item has already been decided (current: '" + item.getItemStatusCode() + "')");
        runPrivileged(context, () -> {
            updateItemStatus(key, newStatus);
            recomputeAppointmentStatus(item.getParentId(), key.isActive());
        });
        AppointmentsItems updated = loadItem(key);
        updated.setItemStatusCriticality(loadItemStatusCriticality(newStatus));
        return updated;
    }

    private Appointments decideAllItems(EventContext context, CqnSelect cqn, String newStatus) {
        requireAnyRole(context, AppointmentSecurityHandler.ROLE_CLIENT);
        boolean isActive = extractIsActive(cqn, context.getModel());
        requireDraft(isActive);
        String id = extractAppointmentId(cqn, context.getModel());
        ensureClientOwnership(context, id);
        if (!AppointmentStatusCode.WAITING_FOR_APPROVAL.equals(loadStatus(id, isActive))) {
            throw badRequest("Items can only be decided while the appointment is waiting for approval");
        }
        List<AppointmentsItems> pending = draftService.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(id)
                                .and(i.IsActiveEntity().eq(false))
                                .and(i.itemStatus_code().eq(AppointmentItemStatusCode.PROPOSED))))
                .listOf(AppointmentsItems.class);
        if (pending.isEmpty()) {
            throw badRequest("No items are pending decision");
        }
        runPrivileged(context, () -> {
            for (AppointmentsItems item : pending) {
                updateItemStatus(new ItemKey(id, item.getPos(), false), newStatus);
            }
            recomputeAppointmentStatus(id, false);
        });
        return loadAppointment(id, isActive);
    }

    private Integer loadItemStatusCriticality(String code) {
        if (code == null) {
            return null;
        }
        return db.run(Select.from(AppointmentItemStatuses_.class)
                        .columns(s -> s.criticality())
                        .where(s -> s.code().eq(code)))
                .first(AppointmentItemStatuses.class)
                .map(AppointmentItemStatuses::getCriticality)
                .orElse(null);
    }

    private AppointmentsItems addLineItem(EventContext context, String stockId, String serviceId) {
        requireAnyRole(context, AppointmentSecurityHandler.ROLE_MECHANIC);
        CqnSelect cqn = (CqnSelect) context.get("cqn");
        boolean isActive = extractIsActive(cqn, context.getModel());
        requireDraft(isActive);
        String parentId = extractAppointmentId(cqn, context.getModel());
        String status = loadStatus(parentId, isActive);
        if (!AppointmentStatusCode.INSPECTION.equals(status) && !AppointmentStatusCode.IN_PROGRESS.equals(status)) {
            throw badRequest("Items can only be added while the appointment is under inspection or in progress");
        }
        int[] pos = new int[1];
        runPrivileged(context, () -> {
            Integer existing = incrementExistingDraftItem(parentId, stockId, serviceId);
            pos[0] = existing != null ? existing : insertNewDraftItem(parentId, stockId, serviceId);
        });
        return loadDraftItem(parentId, pos[0]);
    }

    private int insertNewDraftItem(String parentId, String stockId, String serviceId) {
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
        return nextPos;
    }

    private Integer incrementExistingDraftItem(String parentId, String stockId, String serviceId) {
        List<AppointmentsItems> draftItems = draftService.run(Select.from(AppointmentsItems_.class)
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
                patch.setQuantity(orZero(existing.getQuantity()).add(BigDecimal.ONE));
            } else {
                patch.setDuration(orZero(existing.getDuration()).add(BigDecimal.ONE));
            }
            draftService.patchDraft(Update.entity(AppointmentsItems_.class)
                    .data(patch)
                    .where(i -> i.parent_ID().eq(parentId)
                            .and(i.pos().eq(pos))
                            .and(i.IsActiveEntity().eq(false))));
            return pos;
        }
        return null;
    }

    private void rejectOpenPartItemsInDraft(String parentId) {
        List<AppointmentsItems> draftItems = draftService.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(parentId).and(i.IsActiveEntity().eq(false))))
                .listOf(AppointmentsItems.class);
        for (AppointmentsItems it : draftItems) {
            if (it.getPos() == null
                    || !ItemType.PART.equals(it.getType())
                    || AppointmentItemStatusCode.REJECTED.equals(it.getItemStatusCode())) {
                continue;
            }
            draftService.run(Update.entity(AppointmentsItems_.class)
                    .data(AppointmentsItems.ITEM_STATUS_CODE, AppointmentItemStatusCode.REJECTED)
                    .where(a -> a.parent_ID().eq(parentId)
                            .and(a.pos().eq(it.getPos()))
                            .and(a.IsActiveEntity().eq(false))));
        }
    }

    private void propagateDraftStatusToActive(String id) {
        String draftStatus = loadDraftStatus(id);
        if (draftStatus != null) {
            db.run(Update.entity(Appointments_.class)
                    .data(Appointments.STATUS_CODE, draftStatus)
                    .where(a -> a.ID().eq(id).and(a.IsActiveEntity().eq(true))));
        }
        List<AppointmentsItems> items = draftService.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(id).and(i.IsActiveEntity().eq(false))))
                .listOf(AppointmentsItems.class);
        for (AppointmentsItems it : items) {
            if (it.getItemStatusCode() == null || it.getPos() == null) {
                continue;
            }
            db.run(Update.entity(AppointmentsItems_.class)
                    .data(AppointmentsItems.ITEM_STATUS_CODE, it.getItemStatusCode())
                    .where(a -> a.parent_ID().eq(id)
                            .and(a.pos().eq(it.getPos()))
                            .and(a.IsActiveEntity().eq(true))));
        }
    }

    private int nextPosFor(String parentId) {
        int maxActive = maxPos(db.run(Select.from(AppointmentsItems_.class)
                        .columns(i -> i.pos())
                        .where(i -> i.parent_ID().eq(parentId)))
                .listOf(AppointmentsItems.class));
        int maxDraft = maxPos(draftService.run(Select.from(AppointmentsItems_.class)
                        .columns(i -> i.pos())
                        .where(i -> i.parent_ID().eq(parentId).and(i.IsActiveEntity().eq(false))))
                .listOf(AppointmentsItems.class));
        return Math.max(maxActive, maxDraft) + 10;
    }

    private static int maxPos(List<AppointmentsItems> items) {
        return items.stream()
                .map(AppointmentsItems::getPos)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
    }

    private void requireAnyRole(EventContext context, String... roles) {
        UserInfo user = context.getUserInfo();
        for (String role : roles) {
            if (user.hasRole(role)) {
                return;
            }
        }
        throw new ServiceException(ErrorStatuses.FORBIDDEN,
                "This action requires one of the roles: " + Arrays.toString(roles));
    }

    private void ensureClientOwnership(EventContext context, String id) {
        UserInfo user = context.getUserInfo();
        if (!user.hasRole(AppointmentSecurityHandler.ROLE_CLIENT)
                || user.hasRole(AppointmentSecurityHandler.ROLE_MANAGER)
                || user.hasRole(AppointmentSecurityHandler.ROLE_MECHANIC)) {
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

    private void requireDraft(boolean isActive) {
        if (isActive) {
            throw badRequest("This action can only be invoked on a draft. Please open the appointment in Edit mode first.");
        }
    }

    private String extractAppointmentId(CqnSelect cqn, CdsModel model) {
        Object id = CqnAnalyzer.create(model).analyze(cqn).targetKeys().get(Appointments.ID);
        if (id == null) {
            throw badRequest("Unable to determine appointment ID for status action");
        }
        return String.valueOf(id);
    }

    private boolean extractIsActive(CqnSelect cqn, CdsModel model) {
        Object v = CqnAnalyzer.create(model).analyze(cqn).targetKeys().get(Appointments.IS_ACTIVE_ENTITY);
        return v == null || Boolean.parseBoolean(String.valueOf(v));
    }

    private ItemKey extractItemKey(CqnSelect cqn, CdsModel model) {
        Map<String, Object> keys = CqnAnalyzer.create(model).analyze(cqn).targetKeys();
        Object parentId = keys.get(AppointmentsItems.PARENT_ID);
        Object pos = keys.get(AppointmentsItems.POS);
        Object isActive = keys.get(AppointmentsItems.IS_ACTIVE_ENTITY);
        if (parentId == null || pos == null) {
            throw badRequest("Unable to determine item key");
        }
        return new ItemKey(String.valueOf(parentId),
                Integer.valueOf(String.valueOf(pos)),
                isActive == null || Boolean.parseBoolean(String.valueOf(isActive)));
    }

    private static String parentIdFromContext(EventContext context) {
        Object cqn = context.get("cqn");
        CqnStructuredTypeRef ref = null;
        if (cqn instanceof CqnInsert insert) {
            ref = insert.ref();
        } else if (cqn instanceof CqnUpsert upsert) {
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

    private AppointmentsItems loadItem(ItemKey key) {
        var service = key.isActive() ? db : draftService;
        return service.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(key.parentId())
                                .and(i.pos().eq(key.pos()))
                                .and(i.IsActiveEntity().eq(key.isActive()))))
                .first(AppointmentsItems.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Item not found"));
    }

    private AppointmentsItems loadDraftItem(String parentId, int pos) {
        return draftService.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(parentId)
                                .and(i.pos().eq(pos))
                                .and(i.IsActiveEntity().eq(false))))
                .first(AppointmentsItems.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Item not found"));
    }

    private void updateItemStatus(ItemKey key, String newStatus) {
        var update = Update.entity(AppointmentsItems_.class)
                .data(AppointmentsItems.ITEM_STATUS_CODE, newStatus)
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
        List<AppointmentsItems> items = service.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(appointmentId)
                                .and(i.IsActiveEntity().eq(isActive))))
                .listOf(AppointmentsItems.class);
        if (items.isEmpty()) {
            return;
        }
        if (items.stream().anyMatch(i -> AppointmentItemStatusCode.PROPOSED.equals(i.getItemStatusCode()))) {
            return;
        }
        boolean anyApproved = items.stream().anyMatch(i -> AppointmentItemStatusCode.APPROVED.equals(i.getItemStatusCode()));
        updateAppointmentStatus(appointmentId, isActive,
                anyApproved ? AppointmentStatusCode.IN_PROGRESS : AppointmentStatusCode.CANCELLED);
    }

    private void updateAppointmentStatus(String id, boolean isActive, String newStatus) {
        var update = Update.entity(Appointments_.class)
                .data(Appointments.STATUS_CODE, newStatus)
                .where(a -> a.ID().eq(id).and(a.IsActiveEntity().eq(isActive)));
        if (isActive) {
            db.run(update);
        } else {
            draftService.patchDraft(update);
        }
    }

    private String loadStatus(String id, boolean isActive) {
        var service = isActive ? db : draftService;
        return service.run(Select.from(Appointments_.class)
                        .columns(a -> a.status_code())
                        .where(a -> a.ID().eq(id).and(a.IsActiveEntity().eq(isActive))))
                .first(Appointments.class)
                .map(Appointments::getStatusCode)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Appointment not found: " + id));
    }

    private String loadDraftStatus(String parentId) {
        return draftService.run(Select.from(Appointments_.class)
                        .columns(a -> a.status_code())
                        .where(a -> a.ID().eq(parentId).and(a.IsActiveEntity().eq(false))))
                .first(Appointments.class)
                .map(Appointments::getStatusCode)
                .orElse(null);
    }

    private Appointments loadAppointment(String id, boolean isActive) {
        var service = isActive ? db : draftService;
        return service.run(Select.from(Appointments_.class)
                        .where(a -> a.ID().eq(id).and(a.IsActiveEntity().eq(isActive))))
                .single(Appointments.class);
    }

    private void createWorkItemMasterLogs(String appointmentId, boolean isActive) {
        var service = isActive ? db : draftService;
        List<AppointmentsItems> workItems = service.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(appointmentId).and(i.IsActiveEntity().eq(isActive))))
                .listOf(AppointmentsItems.class).stream()
                .filter(i -> ItemType.WORK.equals(i.getType()))
                .toList();
        if (workItems.isEmpty()) {
            return;
        }
        String appointmentNo = loadAppointment(appointmentId, isActive).getAppointmentNo();
        List<MasterLogs> logs = new ArrayList<>();
        for (AppointmentsItems item : workItems) {
            MasterLogs log = MasterLogs.create();
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

    private static void runPrivileged(EventContext context, Runnable action) {
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            action.run();
        });
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static ServiceException badRequest(String message) {
        return new ServiceException(ErrorStatuses.BAD_REQUEST, message);
    }

    private record ItemKey(String parentId, Integer pos, boolean isActive) {}
}
