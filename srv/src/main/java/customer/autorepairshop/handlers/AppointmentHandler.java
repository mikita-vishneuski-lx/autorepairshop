package customer.autorepairshop.handlers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.sap.cds.ql.Select;
import org.springframework.stereotype.Component;

import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.messages.Messages;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.Appointments_;
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

    @Before(event = {CqnService.EVENT_CREATE, CqnService.EVENT_UPDATE}, entity = AppointmentsItems_.CDS_NAME)
    public void validateStockOnChild(List<AppointmentsItems> appointmentsItems) {
        if(appointmentsItems != null && !appointmentsItems.isEmpty()) {
            performStockValidation(appointmentsItems);
        }
    }

    private void performStockValidation(List<AppointmentsItems> items) {
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
                messages.warn("There may be a delay. Not enough stock for: " + stock.getName());
            }
        }
    }
}
