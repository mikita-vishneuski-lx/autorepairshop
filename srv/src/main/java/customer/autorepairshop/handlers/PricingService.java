package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

import org.springframework.stereotype.Component;

import cds.gen.com.sap.autorepair.ItemType;
import cds.gen.repairservice.AppointmentsItems;

@Component
public class PricingService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public BigDecimal lineTotal(AppointmentsItems item) {
        if (item == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal unitPrice = item.getUnitPrice();
        if (unitPrice == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal factor = ItemType.WORK.equals(item.getType())
                ? item.getDuration()
                : item.getQuantity();
        if (factor == null) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(factor);
    }

    public BigDecimal sumLines(Collection<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .map(this::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Integer partsRatio(Collection<AppointmentsItems> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal parts = BigDecimal.ZERO;
        for (AppointmentsItems item : items) {
            BigDecimal line = lineTotal(item);
            if (line.signum() == 0) {
                continue;
            }
            total = total.add(line);
            if (ItemType.PART.equals(item.getType())) {
                parts = parts.add(line);
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
