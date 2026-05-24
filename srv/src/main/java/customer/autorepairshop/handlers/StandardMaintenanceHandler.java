package customer.autorepairshop.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import org.springframework.stereotype.Component;

import cds.gen.repairservice.Appointments;
import cds.gen.repairservice.AppointmentsApplyStandardMaintenanceContext;
import cds.gen.repairservice.AppointmentsItems;
import cds.gen.repairservice.AppointmentsItems_;
import cds.gen.repairservice.Appointments_;
import cds.gen.repairservice.RepairService_;
import cds.gen.repairservice.ServicesOffered;
import cds.gen.repairservice.ServicesOffered_;
import cds.gen.repairservice.Stocks;
import cds.gen.repairservice.Stocks_;
import cds.gen.com.sap.autorepair.ItemType;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class StandardMaintenanceHandler implements EventHandler {

    static final String STD_OIL_ARTICLE    = "STD-OIL-001";
    static final String STD_FILTER_ARTICLE = "STD-FILTER-001";
    static final String STD_OIL_CHANGE_CODE = "STD-MAINT-001";

    private static final BigDecimal OIL_DEFAULT_QUANTITY    = new BigDecimal("4");
    private static final BigDecimal FILTER_DEFAULT_QUANTITY = BigDecimal.ONE;

    private final PersistenceService db;

    public StandardMaintenanceHandler(PersistenceService db) {
        this.db = db;
    }

    @On(event = AppointmentsApplyStandardMaintenanceContext.CDS_NAME, entity = Appointments_.CDS_NAME)
    public void onApplyStandardMaintenance(AppointmentsApplyStandardMaintenanceContext context) {

        Appointments appointment = db.run(context.getCqn()).single(Appointments.class);
        String appointmentId = appointment.getId();

        // Determine next available position (after existing items)
        List<AppointmentsItems> existingItems = db.run(
                Select.from(AppointmentsItems_.class)
                      .where(i -> i.parent_ID().eq(appointmentId)))
                .listOf(AppointmentsItems.class);

        int nextPos = existingItems.stream()
                .mapToInt(i -> i.getPos() != null ? i.getPos() : 0)
                .max().orElse(0) + 10;

        // Load standard parts by articleNo (single DB round-trip)
        Map<String, Stocks> standardParts = db.run(
                Select.from(Stocks_.class)
                      .where(s -> s.articleNo().in(List.of(STD_OIL_ARTICLE, STD_FILTER_ARTICLE))))
                .listOf(Stocks.class).stream()
                .collect(Collectors.toMap(Stocks::getArticleNo, s -> s));

        // Load standard offered service
        ServicesOffered oilChangeService = db.run(
                Select.from(ServicesOffered_.class)
                      .where(s -> s.workCode().eq(STD_OIL_CHANGE_CODE)))
                .first(ServicesOffered.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Standard maintenance service not found: " + STD_OIL_CHANGE_CODE));

        var newItems = new ArrayList<AppointmentsItems>();

        // 1. Work item — Oil Change Service
        var workItem = AppointmentsItems.create();
        workItem.setParentId(appointmentId);
        workItem.setPos(nextPos);
        workItem.setType(ItemType.WORK);
        workItem.setDescription(oilChangeService.getName());
        workItem.setDuration(formatDuration(oilChangeService.getStandardHour()));
        workItem.setPrice(oilChangeService.getPrice());
        workItem.setCurrencyCode(oilChangeService.getCurrencyCode());
        workItem.setServicesOfferedItemId(oilChangeService.getId());
        newItems.add(workItem);
        nextPos += 10;

        // 2. Part item — Engine Oil
        Stocks oil = standardParts.get(STD_OIL_ARTICLE);
        if (oil == null) {
            throw new ServiceException(ErrorStatuses.NOT_FOUND,
                    "Standard maintenance part not found: " + STD_OIL_ARTICLE);
        }
        var oilItem = AppointmentsItems.create();
        oilItem.setParentId(appointmentId);
        oilItem.setPos(nextPos);
        oilItem.setType(ItemType.PART);
        oilItem.setDescription(oil.getName());
        oilItem.setQuantity(OIL_DEFAULT_QUANTITY);
        oilItem.setPrice(oil.getPrice());
        oilItem.setCurrencyCode(oil.getCurrencyCode());
        oilItem.setStockItemId(oil.getId());
        newItems.add(oilItem);
        nextPos += 10;

        // 3. Part item — Oil Filter
        Stocks filter = standardParts.get(STD_FILTER_ARTICLE);
        if (filter == null) {
            throw new ServiceException(ErrorStatuses.NOT_FOUND,
                    "Standard maintenance part not found: " + STD_FILTER_ARTICLE);
        }
        var filterItem = AppointmentsItems.create();
        filterItem.setParentId(appointmentId);
        filterItem.setPos(nextPos);
        filterItem.setType(ItemType.PART);
        filterItem.setDescription(filter.getName());
        filterItem.setQuantity(FILTER_DEFAULT_QUANTITY);
        filterItem.setPrice(filter.getPrice());
        filterItem.setCurrencyCode(filter.getCurrencyCode());
        filterItem.setStockItemId(filter.getId());
        newItems.add(filterItem);

        db.run(Insert.into(AppointmentsItems_.class).entries(newItems));

        // Return the refreshed appointment
        Appointments updated = db.run(
                Select.from(Appointments_.class)
                      .where(a -> a.ID().eq(appointmentId)))
                .single(Appointments.class);
        context.setResult(updated);
        context.setCompleted();
    }

    private String formatDuration(BigDecimal standardHour) {
        if (standardHour == null) return null;
        return standardHour.stripTrailingZeros().toPlainString() + "h";
    }
}
