using com.sap.autorepair as db from '../db/schema';

using { VehicleSpecsService } from './external/vehicle-specs-service';

@(requires: 'authenticated-user')
service RepairService {

    type VehicleSpecificationsPayload {
        vin            : String(17);
        manufacturer   : String(60);
        model          : String(60);
        engineCode     : String(40);
        enginePowerKw  : Integer;
        fuelType       : String(20);
        transmission   : String(20);
        driveType      : String(20);
        bodyType       : String(20);
        productionYear : Integer;
    }

    action getVehicleSpecificationsByVin(vin : String(17)) returns VehicleSpecificationsPayload;

    @(restrict: [
        { grant: ['READ','CREATE','UPDATE','DELETE'], to: 'Client',   where: 'createdBy = $user' },
        { grant: ['READ','UPDATE'],                    to: 'Mechanic' },
        { grant: '*',                                  to: 'Manager'  }
    ])
    @odata.draft.enabled
    entity Appointments    as
        projection on db.Appointments {
            *,

            case
                status
                when 'New'
                     then 5
                when 'Pending'
                     then 2
                when 'In Inspection'
                     then 2
                when 'Approved'
                     then 3
                when 'In Progress'
                     then 0
                when 'Closed'
                     then 3
                else 0
            end  as statusCriticality : Integer,

            null as partsPercentage   : Integer,

            virtual null as headerFieldControl : Integer,
            virtual null as statusFieldControl : Integer,

            items : redirected to Appointments.Items

        }
        actions {
            action applyStandardMaintenance() returns Appointments;
        };

    entity Appointments.Items as projection on db.Appointments.Items {
        *,
        virtual null as rowFieldControl     : Integer,
        virtual null as typeFieldControl    : Integer,
        virtual null as priceFieldControl   : Integer,
        virtual null as confirmFieldControl : Integer
    };

    @(restrict: [
        { grant: 'READ', to: ['Client','Mechanic'] },
        { grant: 'getAvailableSubstitutes', to: ['Client','Mechanic'] },
        { grant: '*',    to: 'Manager' }
    ])
    entity Stocks          as projection on db.Stocks
        actions {
            function getAvailableSubstitutes() returns many Stocks;
        };

    @(restrict: [
        { grant: 'READ', to: ['Client','Mechanic'] },
        { grant: '*',    to: 'Manager' }
    ])
    entity ServicesOffered as projection on db.OfferedServices;

    @readonly
    entity AppointmentStatuses as projection on db.AppointmentStatuses;

    @(restrict: [
        { grant: 'READ', to: ['Mechanic','Manager'] }
    ])
    entity MasterLogs      as projection on db.MasterLogs;

}
