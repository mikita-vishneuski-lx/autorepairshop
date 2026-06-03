package customer.autorepairshop.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;

import cds.gen.repairservice.GetVehicleSpecificationsByVinContext;
import cds.gen.repairservice.RepairService_;
import cds.gen.repairservice.VehicleSpecificationsPayload;
import cds.gen.vehiclespecsservice.VehicleSpecifications;
import cds.gen.vehiclespecsservice.VehicleSpecifications_;
import cds.gen.vehiclespecsservice.VehicleSpecsService_;

@Component
@ServiceName(RepairService_.CDS_NAME)
public class VehicleSpecificationsHandler implements EventHandler {

    private final CqnService vehicleSpecsService;

    public VehicleSpecificationsHandler(
            @Autowired @Qualifier(VehicleSpecsService_.CDS_NAME) CqnService vehicleSpecsService) {
        this.vehicleSpecsService = vehicleSpecsService;
    }

    @On(event = GetVehicleSpecificationsByVinContext.CDS_NAME)
    public void onGetVehicleSpecificationsByVin(GetVehicleSpecificationsByVinContext context) {
        String vin = context.getVin();
        if (vin == null || vin.isBlank()) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "VIN must be provided.");
        }

        VehicleSpecifications specs = vehicleSpecsService
                .run(Select.from(VehicleSpecifications_.class).where(v -> v.vin().eq(vin)))
                .first(VehicleSpecifications.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Vehicle specifications not found for VIN: " + vin));

        VehicleSpecificationsPayload payload = VehicleSpecificationsPayload.create();
        payload.setVin(specs.getVin());
        payload.setManufacturer(specs.getManufacturer());
        payload.setModel(specs.getModel());
        payload.setEngineCode(specs.getEngineCode());
        payload.setEnginePowerKw(specs.getEnginePowerKw());
        payload.setFuelType(specs.getFuelType());
        payload.setTransmission(specs.getTransmission());
        payload.setDriveType(specs.getDriveType());
        payload.setBodyType(specs.getBodyType());
        payload.setProductionYear(specs.getProductionYear());

        context.setResult(payload);
    }
}
