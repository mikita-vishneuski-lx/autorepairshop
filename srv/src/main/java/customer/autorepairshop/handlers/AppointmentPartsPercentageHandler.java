package customer.autorepairshop.handlers;

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

    private final PersistenceService db;
    private final PricingService pricingService;

    public AppointmentPartsPercentageHandler(PersistenceService db, PricingService pricingService) {
        this.db = db;
        this.pricingService = pricingService;
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
            appointment.setPartsPercentage(
                    pricingService.partsRatio(byParent.getOrDefault(appointment.getId(), List.of())));
        }
    }
}
