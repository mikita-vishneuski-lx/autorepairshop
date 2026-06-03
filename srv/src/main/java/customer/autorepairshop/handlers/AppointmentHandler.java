package customer.autorepairshop.handlers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import cds.gen.com.sap.autorepair.ItemType;
import cds.gen.com.sap.autorepair.Status;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import org.springframework.stereotype.Component;

import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.messages.Messages;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.Appointments_;
import cds.gen.repairservice.MasterLogs;
import cds.gen.repairservice.MasterLogs_;
import cds.gen.repairservice.RepairService_;
import cds.gen.repairservice.Stocks;
import cds.gen.repairservice.Stocks_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class AppointmentHandler implements EventHandler {

    private final PersistenceService db;
    private final Messages messages;

    public AppointmentHandler(Messages messages, PersistenceService db) {
        this.db = db;
        this.messages = messages;
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

    private static final Map<String, Set<String>> ALLOWED_STATUS_TRANSITIONS = Map.of(
            Status.NEW,           Set.of(Status.PENDING,       Status.IN_INSPECTION),
            Status.PENDING,       Set.of(Status.APPROVED,      Status.IN_INSPECTION),
            Status.APPROVED,      Set.of(Status.IN_INSPECTION, Status.INPROGRESS),
            Status.IN_INSPECTION, Set.of(Status.INPROGRESS),
            Status.INPROGRESS,    Set.of(Status.CLOSED),
            Status.CLOSED,        Set.of()
    );

    @Before(event = CqnService.EVENT_UPDATE, entity = Appointments_.CDS_NAME)
    public void validateStatusTransition(List<Appointments> appointments) {
        if (appointments == null || appointments.isEmpty()) {
            return;
        }

        for (Appointments appointment : appointments) {
            validateStatusFlow(appointment);

            if (!Status.INPROGRESS.equals(appointment.getStatus())) {
                continue;
            }

            var appointmentItems = loadAppointmentItems(appointment);

            validateApprovedTasksPresent(appointmentItems);
            validateCriticalPartsAvailable(appointmentItems);
        }
    }

    private void validateStatusFlow(Appointments patch) {
        if (!patch.containsKey(Appointments.STATUS) || patch.getId() == null) {
            return;
        }
        String target = patch.getStatus();
        if (target == null) {
            return;
        }
        String current = db.run(Select.from(Appointments_.class)
                        .columns(a -> a.status())
                        .where(a -> a.ID().eq(patch.getId())))
                .first(Appointments.class)
                .map(Appointments::getStatus)
                .orElse(null);
        if (current == null || current.equals(target)) {
            return;
        }
        Set<String> allowed = ALLOWED_STATUS_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    String.format("Illegal status transition '%s' \u2192 '%s'. Allowed next states: %s",
                            current, target, allowed.isEmpty() ? "<none, terminal state>" : allowed));
        }
    }

    @After(event = CqnService.EVENT_UPDATE, entity = Appointments_.CDS_NAME)
    public void createMasterLogsOnApproval(List<Appointments> appointments) {
        if (appointments == null || appointments.isEmpty()) {
            return;
        }

        for (Appointments appointment : appointments) {
            if (!Status.APPROVED.equals(appointment.getStatus())) {
                continue;
            }

            var workItems = loadAppointmentItems(appointment).stream()
                    .filter(item -> ItemType.WORK.equals(item.getType()))
                    .toList();

            if (workItems.isEmpty()) {
                continue;
            }

            var appointmentNo = appointment.getAppointmentNo();
            if (appointmentNo == null) {
                appointmentNo = db.run(Select.from(Appointments_.class)
                        .columns(a -> a.appointmentNo())
                        .where(a -> a.ID().eq(appointment.getId())))
                        .single(Appointments.class)
                        .getAppointmentNo();
            }

            var logs = new ArrayList<MasterLogs>();
            for (var item : workItems) {
                var log = MasterLogs.create();
                log.setAppointmentId(appointment.getId());
                log.setAppointmentNo(appointmentNo);
                log.setWorkDescription(item.getDescription());
                log.setDuration(item.getDuration());
                log.setPrice(item.getPrice());
                log.setCurrencyCode(item.getCurrencyCode());
                log.setCreatedFromPos(item.getPos());
                logs.add(log);
            }

            db.run(Insert.into(MasterLogs_.class).entries(logs));
        }
    }

    @Before(event = {CqnService.EVENT_CREATE, CqnService.EVENT_UPDATE}, entity = AppointmentsItems_.CDS_NAME)
    public void validateStockOnChild(List<AppointmentsItems> appointmentsItems) {
        if(appointmentsItems != null && !appointmentsItems.isEmpty()) {
            performStockValidation(appointmentsItems);
        }
    }

    private void performStockValidation(List<AppointmentsItems> items) {
        validatePartsAgainstStock(items, false);
    }

    private List<AppointmentsItems> loadAppointmentItems(Appointments appointment) {
        if (appointment.getId() == null) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "Appointment ID is required for status transition validation");
        }

        return db.run(Select.from(AppointmentsItems_.class)
                .where(item -> item.parent_ID().eq(appointment.getId())))
                .listOf(AppointmentsItems.class);
    }

    private void validateApprovedTasksPresent(List<AppointmentsItems> items) {
        var hasApprovedTask = items.stream()
                .map(AppointmentsItems::getType)
                .anyMatch(ItemType.WORK::equals);

        if (!hasApprovedTask) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "The request cannot be set to In Progress without approved tasks in the Items list");
        }
    }

    private void validateCriticalPartsAvailable(List<AppointmentsItems> items) {
        var partItems = items.stream()
                .filter(item -> ItemType.PART.equals(item.getType()))
                .toList();

        if (partItems.isEmpty()) {
            return;
        }

        if (partItems.stream().anyMatch(item -> item.getStockItemId() == null)) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "The request cannot be set to In Progress until the warehouse confirms critical spare parts availability");
        }

        validatePartsAgainstStock(partItems, true);
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
                            "The request cannot be set to In Progress until the warehouse confirms critical spare parts availability");
                }

                messages.warn("There may be a delay. Not enough stock for: " + stock.getName());
            }
        }
    }
}
