package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.services.handler.EventHandler;
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
 