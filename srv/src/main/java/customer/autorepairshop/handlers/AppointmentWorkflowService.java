package customer.autorepairshop.handlers;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;

import cds.gen.com.sap.autorepair.AppointmentItemStatusCode;
import cds.gen.com.sap.autorepair.AppointmentStatusCode;

@Component
public class AppointmentWorkflowService {

    private static final Map<String, Set<String>> APPOINTMENT_TRANSITIONS = Map.of(
            AppointmentStatusCode.CREATED,              Set.of(AppointmentStatusCode.INSPECTION,
                                                               AppointmentStatusCode.CANCELLED),
            AppointmentStatusCode.INSPECTION,           Set.of(AppointmentStatusCode.WAITING_FOR_APPROVAL,
                                                               AppointmentStatusCode.CANCELLED),
            AppointmentStatusCode.WAITING_FOR_APPROVAL, Set.of(AppointmentStatusCode.IN_PROGRESS,
                                                               AppointmentStatusCode.CANCELLED),
            AppointmentStatusCode.IN_PROGRESS,          Set.of(AppointmentStatusCode.COMPLETED,
                                                               AppointmentStatusCode.WAITING_FOR_APPROVAL),
            AppointmentStatusCode.COMPLETED,            Set.of(AppointmentStatusCode.CLOSED),
            AppointmentStatusCode.CLOSED,               Set.of(),
            AppointmentStatusCode.CANCELLED,            Set.of());

    private static final Map<String, Set<String>> ITEM_TRANSITIONS = Map.of(
            AppointmentItemStatusCode.PROPOSED, Set.of(AppointmentItemStatusCode.APPROVED,
                                                       AppointmentItemStatusCode.REJECTED),
            AppointmentItemStatusCode.APPROVED, Set.of(),
            AppointmentItemStatusCode.REJECTED, Set.of());

    private static final Set<String> APPOINTMENT_TERMINAL_STATES = Set.of(
            AppointmentStatusCode.CLOSED, AppointmentStatusCode.CANCELLED);

    private static final Set<String> ITEM_TERMINAL_STATES = Set.of(
            AppointmentItemStatusCode.APPROVED, AppointmentItemStatusCode.REJECTED);

    public Set<String> nextAppointmentStatuses(String from) {
        return APPOINTMENT_TRANSITIONS.getOrDefault(from, Set.of());
    }

    public boolean canTransitionAppointment(String from, String to) {
        return from != null && nextAppointmentStatuses(from).contains(to);
    }

    public void assertAppointmentTransition(String from, String to, String message) {
        if (!canTransitionAppointment(from, to)) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, message);
        }
    }

    public Set<String> nextItemStatuses(String from) {
        return ITEM_TRANSITIONS.getOrDefault(from, Set.of());
    }

    public boolean canTransitionItem(String from, String to) {
        return from != null && nextItemStatuses(from).contains(to);
    }

    public void assertItemTransition(String from, String to, String message) {
        if (!canTransitionItem(from, to)) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, message);
        }
    }

    public boolean isAppointmentTerminal(String status) {
        return APPOINTMENT_TERMINAL_STATES.contains(status);
    }

    public boolean isItemTerminal(String status) {
        return ITEM_TERMINAL_STATES.contains(status);
    }
}
