package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.draft.DraftSaveEventContext;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.com.sap.autorepair.ItemType;
import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.Appointments_;
import cds.gen.repairservice.RepairService_;
import cds.gen.repairservice.ServicesOffered;
import cds.gen.repairservice.ServicesOffered_;
import cds.gen.repairservice.Stocks;
import cds.gen.repairservice.Stocks_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class ItemPricingHandler implements EventHandler {

    private final PersistenceService db;
    private final PricingService pricingService;

    public ItemPricingHandler(PersistenceService db,
                              @Qualifier(RepairService_.CDS_NAME) DraftService draftService,
                              PricingService pricingService) {
        this.db = db;
        this.pricingService = pricingService;
    }

    @Before(event = { CqnService.EVENT_CREATE, CqnService.EVENT_UPDATE,
            DraftService.EVENT_DRAFT_NEW, DraftService.EVENT_DRAFT_PATCH },
            entity = AppointmentsItems_.CDS_NAME)
    public void deriveItemFromSource(List<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (AppointmentsItems item : items) {
            String stockId = item.getStockItemId();
            String serviceId = item.getServicesOfferedItemId();
            if (stockId != null && serviceId != null) {
                throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                        "An item must reference either a stock part or an offered service, not both.");
            }
            if (stockId != null) {
                applyFromStock(item, stockId);
            } else if (serviceId != null) {
                applyFromService(item, serviceId);
            }
        }
    }

    @Before(event = CqnService.EVENT_CREATE, entity = AppointmentsItems_.CDS_NAME)
    public void requireSourceOnPersist(List<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (AppointmentsItems item : items) {
            if (item.getStockItemId() == null && item.getServicesOfferedItemId() == null) {
                throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                        "Each item must reference a stock part or an offered service.");
            }
        }
    }

    @After(event = DraftService.EVENT_DRAFT_SAVE, entity = Appointments_.CDS_NAME)
    public void deriveActiveItemsAfterDraftSave(DraftSaveEventContext context) {
        String appointmentId;
        try {
            var analysis = CqnAnalyzer.create(context.getModel()).analyze(context.getCqn());
            Object id = analysis.targetKeys().get(Appointments.ID);
            if (id == null) {
                return;
            }
            appointmentId = String.valueOf(id);
        } catch (Exception ex) {
            return;
        }
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            var items = db.run(Select.from(AppointmentsItems_.class)
                            .where(i -> i.parent_ID().eq(appointmentId)))
                    .listOf(AppointmentsItems.class);
            for (final AppointmentsItems item : items) {
                String stockId = item.getStockItemId();
                String serviceId = item.getServicesOfferedItemId();
                if ((stockId != null || serviceId != null) && needsDerivation(item)) {
                    AppointmentsItems patch = AppointmentsItems.create();
                    if (stockId != null) {
                        applyFromStock(patch, stockId);
                        if (item.getQuantity() != null) {
                            patch.setQuantity(item.getQuantity());
                        }
                    } else {
                        applyFromService(patch, serviceId);
                        if (item.getDuration() != null) {
                            patch.setDuration(item.getDuration());
                        }
                    }
                    db.run(Update.entity(AppointmentsItems_.class)
                            .data(patch)
                            .where(i -> i.parent_ID().eq(appointmentId).and(i.pos().eq(item.getPos()))));
                    mergeInto(item, patch);
                }
            }
            BigDecimal total = pricingService.sumLines(items);
            db.run(Update.entity(Appointments_.class)
                    .data(Appointments.TOTAL_AMOUNT, total)
                    .where(a -> a.ID().eq(appointmentId)));
        });
    }

    private static AppointmentsItems mergeInto(AppointmentsItems original, AppointmentsItems patch) {
        if (patch.getType() != null) original.setType(patch.getType());
        if (patch.getDescription() != null) original.setDescription(patch.getDescription());
        if (patch.getUnitPrice() != null) original.setUnitPrice(patch.getUnitPrice());
        if (patch.getQuantity() != null) original.setQuantity(patch.getQuantity());
        if (patch.getDuration() != null) original.setDuration(patch.getDuration());
        if (patch.getCurrencyCode() != null) original.setCurrencyCode(patch.getCurrencyCode());
        return original;
    }

    private static boolean needsDerivation(AppointmentsItems item) {
        return item.getType() == null
                || item.getDescription() == null
                || item.getUnitPrice() == null;
    }

    private void applyFromStock(AppointmentsItems item, String stockId) {
        Stocks stock = db.run(Select.from(Stocks_.class).where(s -> s.ID().eq(stockId)))
                .first(Stocks.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.BAD_REQUEST,
                        "Stock item not found: " + stockId));
        item.setType(ItemType.PART);
        item.setDescription(stock.getName());
        item.setUnitPrice(stock.getPrice());
        item.setCurrencyCode(stock.getCurrencyCode());
        item.setDuration(null);
        if (item.getQuantity() == null) {
            item.setQuantity(BigDecimal.ONE);
        }
    }

    private void applyFromService(AppointmentsItems item, String serviceId) {
        ServicesOffered service = db.run(Select.from(ServicesOffered_.class).where(s -> s.ID().eq(serviceId)))
                .first(ServicesOffered.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.BAD_REQUEST,
                        "Offered service not found: " + serviceId));
        item.setType(ItemType.WORK);
        item.setDescription(service.getName());
        item.setUnitPrice(service.getStandardHour());
        item.setCurrencyCode(service.getCurrencyCode());
        item.setQuantity(null);
        if (item.getDuration() == null) {
            item.setDuration(BigDecimal.ONE);
        }
    }
}
