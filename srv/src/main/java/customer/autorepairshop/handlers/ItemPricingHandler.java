package customer.autorepairshop.handlers;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.RepairService_;
import cds.gen.repairservice.ServicesOffered;
import cds.gen.repairservice.ServicesOffered_;
import cds.gen.repairservice.Stocks;
import cds.gen.repairservice.Stocks_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class ItemPricingHandler implements EventHandler {

    private final PersistenceService db;

    public ItemPricingHandler(PersistenceService db) {
        this.db = db;
    }

    @Before(event = CqnService.EVENT_CREATE, entity = AppointmentsItems_.CDS_NAME)
    public void applyMasterDataPricing(List<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (AppointmentsItems item : items) {
            if (item.getStockItemId() != null) {
                applyFromStock(item, item.getStockItemId());
            } else if (item.getServicesOfferedItemId() != null) {
                applyFromService(item, item.getServicesOfferedItemId());
            }
        }
    }

    private void applyFromStock(AppointmentsItems item, String stockId) {
        db.run(Select.from(Stocks_.class).where(s -> s.ID().eq(stockId)))
                .first(Stocks.class)
                .ifPresent(stock -> {
                    item.setPrice(stock.getPrice());
                    item.setCurrencyCode(stock.getCurrencyCode());
                });
    }

    private void applyFromService(AppointmentsItems item, String serviceId) {
        db.run(Select.from(ServicesOffered_.class).where(s -> s.ID().eq(serviceId)))
                .first(ServicesOffered.class)
                .ifPresent(service -> {
                    item.setPrice(service.getPrice());
                    item.setCurrencyCode(service.getCurrencyCode());
                });
    }
}
