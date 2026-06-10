package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.repairservice.RepairService_;
import cds.gen.repairservice.Stocks;
import cds.gen.repairservice.StocksGetAvailableSubstitutesContext;
import cds.gen.repairservice.Stocks_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class StockHandler implements EventHandler {

    private static final String TYPE_ORIGINAL = "Original";
    private static final String TYPE_ANALOG = "Analog";

    private final PersistenceService db;

    public StockHandler(PersistenceService db) {
        this.db = db;
    }

    @Before(event = { CqnService.EVENT_CREATE, CqnService.EVENT_UPDATE,
                      DraftService.EVENT_DRAFT_NEW, DraftService.EVENT_DRAFT_PATCH },
            entity = Stocks_.CDS_NAME)
    public void deriveTypeFromOriginal(List<Stocks> stocks) {
        if (stocks == null) {
            return;
        }
        for (Stocks stock : stocks) {
            if (stock == null) {
                continue;
            }
            boolean originalTouched = stock.containsKey(Stocks.ORIGINAL_ID)
                                      || stock.containsKey("original");
            if (!originalTouched && !stock.containsKey(Stocks.TYPE)) {
                continue;
            }
            String derived = stock.getOriginalId() != null ? TYPE_ANALOG : TYPE_ORIGINAL;
            stock.setType(derived);
        }
    }

    @On(entity = Stocks_.CDS_NAME)
    public void onGetAvailableSustitutes(StocksGetAvailableSubstitutesContext context) {

        CqnSelect selectOriginalPart = context.getCqn();

        Stocks selectedPart = db.run(selectOriginalPart).first(Stocks.class).orElse(null);

        if (selectedPart == null || selectedPart.getType() == null) {
            context.setResult(List.of());
            return;
        }

        CqnSelect findSubstitutesQuery;

        if (TYPE_ORIGINAL.equals(selectedPart.getType())) {
            findSubstitutesQuery = Select.from(Stocks_.class)
                                        .where(s -> s.type().eq(TYPE_ANALOG)
                                            .and(s.original_ID().eq(selectedPart.getId()))
                                            .and(s.quantity().gt(BigDecimal.ZERO)));
        }

        else if (TYPE_ANALOG.equals(selectedPart.getType()) && selectedPart.getOriginalId() != null) {
             String baseOriginalId = selectedPart.getOriginalId();

             findSubstitutesQuery = Select.from(Stocks_.class)
                                        .where(s -> s.quantity().gt(BigDecimal.ZERO)
                                            .and(
                                                s.ID().eq(baseOriginalId)
                                                .or(
                                                    s.original_ID().eq(baseOriginalId)
                                                        .and(s.ID().ne(selectedPart.getId()))
                                                )
                                            ));
        } else {
            context.setResult(List.of());
            return;
        }

        List<Stocks> availableSubstitutes = db.run(findSubstitutesQuery).listOf(Stocks.class);

        context.setResult(availableSubstitutes);
    }
}

