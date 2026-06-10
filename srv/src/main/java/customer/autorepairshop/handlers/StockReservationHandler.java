package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.AnalysisResult;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.draft.DraftSaveEventContext;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.HandlerOrder;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.com.sap.autorepair.ItemType;
import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.Appointments_;
import cds.gen.repairservice.RepairService_;
import cds.gen.repairservice.Stocks;
import cds.gen.repairservice.Stocks_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class StockReservationHandler implements EventHandler {

    private static final String STATUS_REJECTED = "Rejected";
    private static final int LOCK_TIMEOUT_SECONDS = 10;

    private final PersistenceService db;
    private final DraftService draftService;

    public StockReservationHandler(PersistenceService db,
                                   @Qualifier(RepairService_.CDS_NAME) DraftService draftService) {
        this.db = db;
        this.draftService = draftService;
    }

    @Before(event = DraftService.EVENT_DRAFT_SAVE, entity = Appointments_.CDS_NAME)
    @HandlerOrder(HandlerOrder.EARLY)
    public void reserveOnDraftSave(DraftSaveEventContext context) {
        String parentId = extractParentId(context);
        if (parentId == null) {
            return;
        }
        context.getCdsRuntime().requestContext().privilegedUser().run(ctx -> {
            applyReservationDelta(parentId);
        });
    }

    private void applyReservationDelta(String parentId) {
        List<AppointmentsItems> draftItems = draftService.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(parentId).and(i.IsActiveEntity().eq(false))))
                .listOf(AppointmentsItems.class);
        List<AppointmentsItems> activeItems = db.run(Select.from(AppointmentsItems_.class)
                        .where(i -> i.parent_ID().eq(parentId).and(i.IsActiveEntity().eq(true))))
                .listOf(AppointmentsItems.class);

        Map<String, BigDecimal> activeReserved = reservedByStock(activeItems);
        Map<String, BigDecimal> draftReserved  = reservedByStock(draftItems);

        Set<String> stockIds = new HashSet<>();
        stockIds.addAll(activeReserved.keySet());
        stockIds.addAll(draftReserved.keySet());

        for (String stockId : stockIds) {
            BigDecimal oldQty = activeReserved.getOrDefault(stockId, BigDecimal.ZERO);
            BigDecimal newQty = draftReserved.getOrDefault(stockId, BigDecimal.ZERO);
            BigDecimal delta = newQty.subtract(oldQty);
            if (delta.signum() == 0) {
                continue;
            }
            adjustStock(stockId, delta);
        }
    }

    private Map<String, BigDecimal> reservedByStock(List<AppointmentsItems> items) {
        Map<String, BigDecimal> result = new HashMap<>();
        for (AppointmentsItems item : items) {
            if (!isReserving(item)) {
                continue;
            }
            result.merge(item.getStockItemId(), reservedQty(item), BigDecimal::add);
        }
        return result;
    }

    private void adjustStock(String stockId, BigDecimal reserveDelta) {
        Stocks stock = db.run(Select.from(Stocks_.class)
                        .where(s -> s.ID().eq(stockId))
                        .lock(LOCK_TIMEOUT_SECONDS))
                .first(Stocks.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Stock not found: " + stockId));
        BigDecimal current = stock.getQuantity() == null ? BigDecimal.ZERO : stock.getQuantity();
        BigDecimal next = current.subtract(reserveDelta);
        if (next.signum() < 0) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "Insufficient stock for " + stock.getArticleNo()
                            + " (available " + current + ", requested " + reserveDelta + ")");
        }
        db.run(Update.entity(Stocks_.class)
                .data(Stocks.QUANTITY, next)
                .where(s -> s.ID().eq(stockId)));
    }

    private boolean isReserving(AppointmentsItems item) {
        if (item == null) {
            return false;
        }
        if (!ItemType.PART.equals(item.getType())) {
            return false;
        }
        if (item.getStockItemId() == null) {
            return false;
        }
        if (STATUS_REJECTED.equals(item.getItemStatus())) {
            return false;
        }
        return reservedQty(item).signum() > 0;
    }

    private BigDecimal reservedQty(AppointmentsItems item) {
        BigDecimal q = item.getQuantity();
        return q == null ? BigDecimal.ZERO : q;
    }

    private String extractParentId(DraftSaveEventContext context) {
        CdsModel model = context.getModel();
        AnalysisResult analysis = CqnAnalyzer.create(model).analyze(context.getCqn());
        Object idValue = analysis.targetKeys().get(Appointments.ID);
        return idValue == null ? null : String.valueOf(idValue);
    }
}
