package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.UserInfo;

import cds.gen.com.sap.autorepair.ItemType;
import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.Appointments_;
import cds.gen.repairservice.RepairService_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class AppointmentSecurityHandler implements EventHandler {

    static final String ROLE_CLIENT = "Client";
    static final String ROLE_MECHANIC = "Mechanic";
    static final String ROLE_MANAGER = "Manager";

    static final String FIELD_CONFIRMED_BY_CLIENT = "confirmedByClient";

    private static final Set<String> CLIENT_WRITABLE_ITEM = Set.of(
            FIELD_CONFIRMED_BY_CLIENT);

    private static final Set<String> MECHANIC_WRITABLE_ITEM = Set.of(
            AppointmentsItems.QUANTITY,
            AppointmentsItems.DURATION,
            AppointmentsItems.STOCK_ITEM_ID,
            AppointmentsItems.SERVICES_OFFERED_ITEM_ID);

    private static final Set<String> CANCELLABLE_STATES = Set.of(
            "Created",
            "Inspection",
            "Waiting for approval");

    private static final Set<String> CLIENT_CONFIRM_EDITABLE_STATES = Set.of(
            "Waiting for approval");

    private static final Set<String> TECHNICAL_FIELDS = Set.of(
            "ID", "parent", "parent_ID", "pos",
            "createdAt", "createdBy", "modifiedAt", "modifiedBy",
            "IsActiveEntity", "HasActiveEntity", "HasDraftEntity",
            "DraftAdministrativeData", "DraftAdministrativeData_DraftUUID");

    private static final int FC_HIDDEN    = 0;
    private static final int FC_READ_ONLY = 1;
    private static final int FC_EDITABLE  = 3;
    private static final int FC_MANDATORY = 7;

    private final PersistenceService db;
    private final UserInfo userInfo;

    public AppointmentSecurityHandler(PersistenceService db, UserInfo userInfo) {
        this.db = db;
        this.userInfo = userInfo;
    }

    @After(event = CqnService.EVENT_READ, entity = Appointments_.CDS_NAME)
    public void computeFieldControl(List<Appointments> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        boolean isManager  = userInfo.hasRole(ROLE_MANAGER);
        boolean isMechanic = userInfo.hasRole(ROLE_MECHANIC);
        boolean isClient   = userInfo.hasRole(ROLE_CLIENT);

        for (Appointments row : rows) {
            String status = row.getStatus();
            boolean isDraft = Boolean.FALSE.equals(row.getIsActiveEntity());
            boolean headerEditable = isClient && "Created".equals(status);
            row.put("headerFieldControl", headerEditable ? FC_EDITABLE : FC_READ_ONLY);

            row.put("canApplyStandardMaintenance", isMechanic && isDraft && "Inspection".equals(status));
            row.put("canStartInspection",          isManager  && isDraft && "Created".equals(status));
            row.put("canRequestApproval",          isMechanic && isDraft && "Inspection".equals(status));
            row.put("canComplete",                 isMechanic && isDraft && "In Progress".equals(status));
            row.put("canClose",                    isMechanic && isDraft && "Completed".equals(status));
            row.put("canCancel",                   (isClient || isManager) && isDraft && CANCELLABLE_STATES.contains(status));
            row.put("canAddItems",                 isMechanic && isDraft
                    && ("Inspection".equals(status) || "In Progress".equals(status)));
        }
    }

    @After(event = CqnService.EVENT_READ, entity = AppointmentsItems_.CDS_NAME)
    public void computeItemFieldControl(List<AppointmentsItems> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        boolean isClient   = userInfo.hasRole(ROLE_CLIENT);
        boolean isMechanic = userInfo.hasRole(ROLE_MECHANIC);

        for (AppointmentsItems row : rows) {
            String parentStatus = loadParentStatus(row);

            boolean canEditRow;
            int confirmControl;
            if (isMechanic) {
                canEditRow     = "Inspection".equals(parentStatus);
                confirmControl = FC_READ_ONLY;
            } else if (isClient) {
                canEditRow     = false;
                confirmControl = (parentStatus != null
                        && CLIENT_CONFIRM_EDITABLE_STATES.contains(parentStatus))
                        ? FC_EDITABLE : FC_READ_ONLY;
            } else {
                canEditRow     = false;
                confirmControl = FC_READ_ONLY;
            }
            row.put("typeFieldControl",    canEditRow ? FC_MANDATORY : FC_READ_ONLY);
            row.put("priceFieldControl",   FC_READ_ONLY);
            row.put("confirmFieldControl", confirmControl);

            int rowControl = canEditRow ? FC_EDITABLE : FC_READ_ONLY;
            boolean isWork = ItemType.WORK.equals(row.getType());
            boolean isPart = ItemType.PART.equals(row.getType());
            row.put("quantityFieldControl", isWork ? FC_HIDDEN : (isPart ? rowControl : FC_READ_ONLY));
            row.put("durationFieldControl", isPart ? FC_HIDDEN : (isWork ? rowControl : FC_READ_ONLY));

            boolean canDecide = isClient
                    && Boolean.FALSE.equals(row.getIsActiveEntity())
                    && "Proposed".equals(row.getItemStatus())
                    && parentStatus != null
                    && CLIENT_CONFIRM_EDITABLE_STATES.contains(parentStatus);
            row.put("canApproveItem", canDecide);
            row.put("canRejectItem",  canDecide);
        }
    }

    @Before(event = { CqnService.EVENT_UPDATE, DraftService.EVENT_DRAFT_PATCH }, entity = Appointments_.CDS_NAME)
    public void enforceAppointmentUpdate(List<Appointments> appointments) {
        if (appointments == null || appointments.isEmpty() || userInfo.isPrivileged()) {
            return;
        }
        boolean isClient   = userInfo.hasRole(ROLE_CLIENT);
        boolean isMechanic = userInfo.hasRole(ROLE_MECHANIC);
        boolean isManager  = userInfo.hasRole(ROLE_MANAGER);

        for (Appointments patch : appointments) {
            Appointments current = loadCurrentAppointment(patch);
            Set<String> changed = changedFields(patch, current);
            if (changed.isEmpty()) {
                continue;
            }

            if (changed.contains(Appointments.STATUS)) {
                throw forbidden(
                        "Status can only be changed through workflow actions (startInspection, requestApproval, approveItem, rejectItem, complete, close, cancel).");
            }

            if (isMechanic || isManager) {
                throw forbidden(
                        "Only the client may edit appointment header fields.");
            }
            if (isClient) {
                String currentStatus = current != null ? current.getStatus() : null;
                if (!"Created".equals(currentStatus)) {
                    throw forbidden(
                            "Header fields can only be edited while the appointment is in 'Created' status.");
                }
            }
        }
    }

    @Before(event = { CqnService.EVENT_CREATE, DraftService.EVENT_DRAFT_NEW },
            entity = AppointmentsItems_.CDS_NAME)
    public void enforceItemCreate(com.sap.cds.services.EventContext context, List<AppointmentsItems> items) {
        if (items == null || items.isEmpty() || userInfo.isPrivileged()) {
            return;
        }
        if (!userInfo.hasRole(ROLE_MECHANIC)) {
            throw forbidden(
                    "Only mechanics may add work / parts items.");
        }
        String contextParentId = parentIdFromContext(context);
        for (AppointmentsItems item : items) {
            if (item.getParentId() == null && contextParentId != null) {
                item.setParentId(contextParentId);
            }
            String parentStatus = loadParentStatus(item);
            if (!"Inspection".equals(parentStatus) && !"In Progress".equals(parentStatus)) {
                throw forbidden(
                        "Items can only be added while the appointment is under inspection or in progress.");
            }
        }
    }

    private static String parentIdFromContext(com.sap.cds.services.EventContext context) {
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
        com.sap.cds.ql.cqn.AnalysisResult analysis =
                com.sap.cds.ql.cqn.CqnAnalyzer.create(context.getModel()).analyze(ref);
        Object id = analysis.targetKeys().get("parent_ID");
        if (id == null) {
            id = analysis.rootKeys().get("ID");
        }
        return id == null ? null : id.toString();
    }

    @Before(event = { CqnService.EVENT_UPDATE, DraftService.EVENT_DRAFT_PATCH },
            entity = AppointmentsItems_.CDS_NAME)
    public void enforceItemUpdate(List<AppointmentsItems> items) {
        if (items == null || items.isEmpty() || userInfo.isPrivileged()) {
            return;
        }
        boolean isClient   = userInfo.hasRole(ROLE_CLIENT);
        boolean isMechanic = userInfo.hasRole(ROLE_MECHANIC);

        for (AppointmentsItems item : items) {
            AppointmentsItems current = loadCurrentItem(item);
            Set<String> changed = changedItemFields(item, current);
            if (changed.isEmpty()) {
                continue;
            }

            String parentStatus = loadParentStatus(item);

            if (isClient) {
                Set<String> illegal = changed.stream()
                        .filter(f -> !CLIENT_WRITABLE_ITEM.contains(f))
                        .collect(Collectors.toSet());
                if (!illegal.isEmpty()) {
                    throw forbidden(
                            "Clients may only toggle item confirmation; forbidden fields: " + illegal);
                }
                if (parentStatus == null || !CLIENT_CONFIRM_EDITABLE_STATES.contains(parentStatus)) {
                    throw forbidden(
                            "Item confirmation can only be toggled while the appointment is waiting for approval.");
                }
                continue;
            }
            if (isMechanic) {
                if (!"Inspection".equals(parentStatus)) {
                    throw forbidden(
                            "Items can only be modified while the appointment is under inspection.");
                }
                Set<String> illegal = changed.stream()
                        .filter(f -> !MECHANIC_WRITABLE_ITEM.contains(f))
                        .collect(Collectors.toSet());
                if (!illegal.isEmpty()) {
                    throw forbidden(
                            "Mechanics may only set quantity or pick a stock part / offered service; forbidden fields: "
                                    + illegal);
                }
                continue;
            }
            throw forbidden("Managers may not modify items.");
        }
    }

    @Before(event = { CqnService.EVENT_DELETE, DraftService.EVENT_DRAFT_CANCEL },
            entity = AppointmentsItems_.CDS_NAME)
    public void enforceItemDelete(com.sap.cds.services.EventContext context) {
        if (userInfo.isPrivileged()) {
            return;
        }
        if (!userInfo.hasRole(ROLE_MECHANIC)) {
            throw forbidden(
                    "Only mechanics may remove work / parts items.");
        }
        Object cqn = context.get("cqn");
        if (!(cqn instanceof com.sap.cds.ql.cqn.CqnDelete deleteCqn)) {
            return;
        }
        Map<String, Object> keys = com.sap.cds.ql.cqn.CqnAnalyzer.create(context.getModel())
                .analyze(deleteCqn.ref()).targetKeys();
        Object parentId = keys.get("parent_ID");
        if (parentId == null) {
            return;
        }
        String parentStatus = db.run(Select.from(Appointments_.class)
                        .columns(a -> a.status())
                        .where(a -> a.ID().eq(parentId.toString())))
                .first(Appointments.class)
                .map(Appointments::getStatus)
                .orElse(null);
        if (!"Inspection".equals(parentStatus)) {
            throw forbidden(
                    "Items can only be deleted while the appointment is under inspection.");
        }
    }

    private static ServiceException forbidden(String message) {
        return new ServiceException(ErrorStatuses.FORBIDDEN, message);
    }

    private Appointments loadCurrentAppointment(Appointments patch) {
        if (patch.getId() == null) {
            return null;
        }
        return db.run(Select.from(Appointments_.class)
                        .where(a -> a.ID().eq(patch.getId())))
                .first(Appointments.class)
                .orElse(null);
    }

    private AppointmentsItems loadCurrentItem(AppointmentsItems item) {
        String parentId = item.getParentId();
        Integer pos = item.getPos();
        if (parentId == null || pos == null) {
            return null;
        }
        return db.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(parentId).and(i.pos().eq(pos))))
                .first(AppointmentsItems.class)
                .orElse(null);
    }

    private String loadParentStatus(AppointmentsItems item) {
        String parentId = item.getParentId();
        if (parentId == null) {
            return null;
        }
        return db.run(Select.from(Appointments_.class)
                        .columns(a -> a.status())
                        .where(a -> a.ID().eq(parentId)))
                .first(Appointments.class)
                .map(Appointments::getStatus)
                .orElse(null);
    }

    private Set<String> changedFields(Appointments patch, Appointments current) {
        return diffKeys(patch, current);
    }

    private Set<String> changedItemFields(AppointmentsItems patch, AppointmentsItems current) {
        return diffKeys(patch, current);
    }

    private static Set<String> diffKeys(Map<String, Object> patch, Map<String, Object> current) {
        return patch.entrySet().stream()
                .filter(e -> !TECHNICAL_FIELDS.contains(e.getKey()))
                .filter(e -> !(e.getValue() instanceof Iterable) && !(e.getValue() instanceof Map))
                .filter(e -> current == null || !valuesEqual(e.getValue(), current.get(e.getKey())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        if (a instanceof BigDecimal ba && b instanceof BigDecimal bb) {
            return ba.compareTo(bb) == 0;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return new BigDecimal(na.toString()).compareTo(new BigDecimal(nb.toString())) == 0;
        }
        return false;
    }
}
