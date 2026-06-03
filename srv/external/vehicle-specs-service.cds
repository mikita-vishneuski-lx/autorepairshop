service VehicleSpecsService {
    entity VehicleSpecifications {
        key vin           : String(17);
        manufacturer      : String(60);
        model             : String(60);
        engineCode        : String(40);
        enginePowerKw     : Integer;
        fuelType          : String(20);
        transmission      : String(20);
        driveType         : String(20);
        bodyType          : String(20);
        productionYear    : Integer;
    }
}
