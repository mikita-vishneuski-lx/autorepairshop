package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.Appointments_;
import cds.gen.repairservice.RepairService_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class AppointmentPartsPercentageHandler implements EventHandler {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String PART_TYPE = "Part";

    private final PersistenceService db;

    public AppointmentPartsPercentageHandler(PersistenceService db) {
        this.db = db;
    }

    @After(event = CqnService.EVENT_READ, entity = Appointments_.CDS_NAME)
    public void computePartsPercentage(List<Appointments> appointments) {
        if (appointments == null || appointments.isEmpty()) {
            return;
        }

        Set<String> ids = appointments.stream()
                .map(Appointments::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (ids.isEmpty()) {
            return;
        }

        List<AppointmentsItems> items = db.run(Select.from(AppointmentsItems_.class)
                .where(i -> i.parent_ID().in(ids)))
                .listOf(AppointmentsItems.class);

        Map<String, List<AppointmentsItems>> byParent = items.stream()
                .filter(it -> it.getParentId() != null)
                .collect(Collectors.groupingBy(AppointmentsItems::getParentId));

        for (Appointments appointment : appointments) {
            appointment.setPartsPercentage(calculate(byParent.getOrDefault(appointment.getId(), List.of())));
        }
    }

    private Integer calculate(List<AppointmentsItems> items) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal parts = BigDecimal.ZERO;

        for (AppointmentsItems item : items) {
            BigDecimal lineTotal = ItemPricingHandler.lineTotal(item);
            if (lineTotal.signum() == 0) {
                continue;
            }
            total = total.add(lineTotal);
            if (PART_TYPE.equals(item.getType())) {
                parts = parts.add(lineTotal);
            }
        }

        if (total.signum() == 0) {
            return 0;
        }

        return parts.multiply(HUNDRED)
                .divide(total, 0, RoundingMode.HALF_UP)
                .intValue();
    }
}
