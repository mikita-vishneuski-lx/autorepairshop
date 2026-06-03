package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.HashSet;
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

    private static final Set<String> CLIENT_WRITABLE = Set.of(
            Appointments.STATUS);

    private static final Set<String> CLIENT_WRITABLE_ITEM = Set.of(
            FIELD_CONFIRMED_BY_CLIENT);

    private static final Set<String> HEADER_PROTECTED = Set.of(
            Appointments.VIN,
            Appointments.BRAND,
            Appointments.CAR_NUMBER,
            Appointments.PRODUCTION_YEAR,
            Appointments.DESCRIPTION,
            Appointments.ESTIMATED_AMOUNT);

    private static final Set<String> MECHANIC_PROTECTED = Set.of(
            Appointments.STATUS,
            Appointments.APPOINTMENT_NO);

    private static final Set<String> ITEM_PRICING_PROTECTED = Set.of(
            AppointmentsItems.PRICE,
            AppointmentsItems.CURRENCY_CODE);

    private static final Map<String, Set<String>> CLIENT_STATUS_TRANSITIONS = Map.of(
            "Pending", Set.of("Approved"));

    private static final Set<String> POST_INSPECTION_STATES = Set.of(
            "In Inspection", "In Progress", "Closed");

    private static final Set<String> TECHNICAL_FIELDS = Set.of(
            "ID", "parent", "parent_ID", "pos",
            "createdAt", "createdBy", "modifiedAt", "modifiedBy",
            "IsActiveEntity", "HasActiveEntity", "HasDraftEntity",
            "DraftAdministrativeData", "DraftAdministrativeData_DraftUUID");

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
        boolean isClient   = userInfo.hasRole(ROLE_CLIENT);
        boolean isMechanic = userInfo.hasRole(ROLE_MECHANIC);
        boolean isManager  = userInfo.hasRole(ROLE_MANAGER);

        for (Appointments row : rows) {
            String status = row.getStatus();
            boolean inspectionLocked = status != null && POST_INSPECTION_STATES.contains(status);

            int header;
            int statusControl;

            if (isManager) {
                header        = inspectionLocked ? FC_READ_ONLY : FC_EDITABLE;
                statusControl = FC_EDITABLE;
            } else if (isMechanic) {
                header        = FC_READ_ONLY;
                statusControl = FC_READ_ONLY;
            } else if (isClient) {
                header        = FC_READ_ONLY;
                statusControl = CLIENT_STATUS_TRANSITIONS.containsKey(status) ? FC_EDITABLE : FC_READ_ONLY;
            } else {
                header        = FC_READ_ONLY;
                statusControl = FC_READ_ONLY;
            }
            row.put("headerFieldControl", header);
            row.put("statusFieldControl", statusControl);
        }
    }

    @After(event = CqnService.EVENT_READ, entity = AppointmentsItems_.CDS_NAME)
    public void computeItemFieldControl(List<AppointmentsItems> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        boolean isClient   = userInfo.hasRole(ROLE_CLIENT);
        boolean isMechanic = userInfo.hasRole(ROLE_MECHANIC);
        boolean isManager  = userInfo.hasRole(ROLE_MANAGER);

        for (AppointmentsItems row : rows) {
            String parentStatus = loadParentStatus(row);
            boolean inspectionLocked = parentStatus != null && POST_INSPECTION_STATES.contains(parentStatus);

            boolean canEditRow;
            int confirmControl;
            if (isMechanic) {
                canEditRow     = true;
                confirmControl = FC_READ_ONLY;
            } else if (isManager) {
                canEditRow     = !inspectionLocked;
                confirmControl = FC_READ_ONLY;
            } else if (isClient) {
                canEditRow     = false;

                confirmControl = inspectionLocked ? FC_READ_ONLY : FC_EDITABLE;
            } else {
                canEditRow     = false;
                confirmControl = FC_READ_ONLY;
            }
            row.put("rowFieldControl",     canEditRow ? FC_EDITABLE  : FC_READ_ONLY);
            row.put("typeFieldControl",    canEditRow ? FC_MANDATORY : FC_READ_ONLY);

            row.put("priceFieldControl",   FC_READ_ONLY);
            row.put("confirmFieldControl", confirmControl);
        }
    }

    @Before(event = { CqnService.EVENT_UPDATE, DraftService.EVENT_DRAFT_PATCH }, entity = Appointments_.CDS_NAME)
    public void enforceAppointmentUpdate(List<Appointments> appointments) {
        if (appointments == null || appointments.isEmpty()) {
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

            if (isManager) {
                if (current != null && POST_INSPECTION_STATES.contains(current.getStatus())) {
                    Set<String> bad = intersect(changed, HEADER_PROTECTED);
                    if (!bad.isEmpty()) {
                        throw forbidden(
                                "Header fields are locked once inspection has started: " + bad);
                    }
                }
            } else if (isMechanic) {
                Set<String> bad = intersect(changed, HEADER_PROTECTED);
                bad.addAll(intersect(changed, MECHANIC_PROTECTED));
                if (!bad.isEmpty()) {
                    throw forbidden(
                            "Mechanics may only maintain items; forbidden fields: " + bad);
                }
            } else if (isClient) {
                Set<String> illegal = changed.stream()
                        .filter(f -> !CLIENT_WRITABLE.contains(f))
                        .collect(Collectors.toSet());
                if (!illegal.isEmpty()) {
                    throw forbidden(
                            "Clients may only update status / confirmation; forbidden fields: " + illegal);
                }
                validateClientStatusTransition(patch, current);
            }
        }
    }

    @Before(event = { CqnService.EVENT_CREATE, DraftService.EVENT_DRAFT_NEW },
            entity = AppointmentsItems_.CDS_NAME)
    public void enforceItemCreate(List<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (isPureClient()) {
            throw forbidden(
                    "Clients cannot add work / parts items; only the technician maintains them.");
        }
    }

    @Before(event = { CqnService.EVENT_UPDATE, DraftService.EVENT_DRAFT_PATCH },
            entity = AppointmentsItems_.CDS_NAME)
    public void enforceItemUpdate(List<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        boolean isClient   = userInfo.hasRole(ROLE_CLIENT);
        boolean isMechanic = userInfo.hasRole(ROLE_MECHANIC);
        boolean isManager  = userInfo.hasRole(ROLE_MANAGER);

        for (AppointmentsItems item : items) {
            AppointmentsItems current = loadCurrentItem(item);
            Set<String> changed = changedItemFields(item, current);
            if (changed.isEmpty()) {
                continue;
            }

            if (isClient && !isMechanic && !isManager) {

                Set<String> illegal = changed.stream()
                        .filter(f -> !CLIENT_WRITABLE_ITEM.contains(f))
                        .collect(Collectors.toSet());
                if (!illegal.isEmpty()) {
                    throw forbidden(
                            "Clients may only toggle item confirmation; forbidden fields: " + illegal);
                }
                String parentStatus = loadParentStatus(item);
                if (parentStatus != null && POST_INSPECTION_STATES.contains(parentStatus)) {
                    throw forbidden(
                            "Item confirmation is locked once inspection has started.");
                }
                continue;
            }
            if (isManager) {
                String parentStatus = loadParentStatus(item);
                if (parentStatus != null && POST_INSPECTION_STATES.contains(parentStatus)) {
                    Set<String> bad = intersect(changed, ITEM_PRICING_PROTECTED);
                    if (!bad.isEmpty()) {
                        throw forbidden(
                                "Item pricing is derived from master data and cannot be set manually: " + bad);
                    }
                }
            } else if (isMechanic) {
                Set<String> bad = intersect(changed, ITEM_PRICING_PROTECTED);
                bad.addAll(intersect(changed, CLIENT_WRITABLE_ITEM));
                if (!bad.isEmpty()) {
                    throw forbidden(
                            "Mechanics may not change item pricing or client confirmation: " + bad);
                }
            }
        }
    }

    @Before(event = { CqnService.EVENT_DELETE, DraftService.EVENT_DRAFT_CANCEL },
            entity = AppointmentsItems_.CDS_NAME)
    public void enforceItemDelete() {
        if (isPureClient()) {
            throw forbidden(
                    "Clients cannot remove work / parts items; only the technician maintains them.");
        }
    }

    private boolean isPureClient() {
        return userInfo.hasRole(ROLE_CLIENT)
                && !userInfo.hasRole(ROLE_MECHANIC)
                && !userInfo.hasRole(ROLE_MANAGER);
    }

    private static ServiceException forbidden(String message) {
        return new ServiceException(ErrorStatuses.FORBIDDEN, message);
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        return a.stream().filter(b::contains).collect(Collectors.toCollection(HashSet::new));
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

    private void validateClientStatusTransition(Appointments patch, Appointments current) {
        if (!patch.containsKey(Appointments.STATUS) || current == null) {
            return;
        }
        String from = current.getStatus();
        String to   = patch.getStatus();
        if (Objects.equals(from, to)) {
            return;
        }
        Set<String> allowed = CLIENT_STATUS_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw forbidden(String.format(
                    "Clients may not transition status from '%s' to '%s'", from, to));
        }
    }
}
