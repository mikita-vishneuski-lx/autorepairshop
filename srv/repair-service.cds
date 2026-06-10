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
        { grant: 'CREATE', to: 'Client' },
        { grant: ['READ','UPDATE','cancel'], to: 'Client',   where: 'createdBy = $user' },
        { grant: ['READ','UPDATE','applyStandardMaintenance','requestApproval','complete','close','addPart','addWork'], to: 'Mechanic' },
        { grant: ['READ','UPDATE','startInspection','cancel'], to: 'Manager'  }
    ])
    @odata.draft.enabled
    entity Appointments    as
        projection on db.Appointments {
            *,

            case
                status
                when 'Created'
                     then 5
                when 'Inspection'
                     then 2
                when 'Waiting for approval'
                     then 2
                when 'In Progress'
                     then 0
                when 'Completed'
                     then 3
                when 'Closed'
                     then 3
                when 'Cancelled'
                     then 1
                else 0
            end  as statusCriticality : Integer,

            null as partsPercentage   : Integer,

            virtual null as headerFieldControl : Integer,

            virtual null as canApplyStandardMaintenance : Boolean,
            virtual null as canStartInspection          : Boolean,
            virtual null as canRequestApproval          : Boolean,
            virtual null as canComplete                 : Boolean,
            virtual null as canClose                    : Boolean,
            virtual null as canCancel                   : Boolean,
            virtual null as canAddItems                 : Boolean,

            items : redirected to Appointments.Items

        }
        actions {
            action applyStandardMaintenance() returns Appointments;
            action startInspection() returns Appointments;
            action requestApproval() returns Appointments;
            action complete() returns Appointments;
            action close() returns Appointments;
            action cancel() returns Appointments;
            action addPart(stockItem : UUID @title: 'Part' @Common.ValueList: {
                CollectionPath: 'Stocks',
                Parameters    : [
                    { $Type: 'Common.ValueListParameterInOut',       LocalDataProperty: stockItem, ValueListProperty: 'ID' },
                    { $Type: 'Common.ValueListParameterDisplayOnly', ValueListProperty: 'articleNo' },
                    { $Type: 'Common.ValueListParameterDisplayOnly', ValueListProperty: 'name' },
                    { $Type: 'Common.ValueListParameterDisplayOnly', ValueListProperty: 'price' },
                    { $Type: 'Common.ValueListParameterDisplayOnly', ValueListProperty: 'currency_code' }
                ]
            }) returns Appointments.Items;
            action addWork(servicesOfferedItem : UUID @title: 'Work' @Common.ValueList: {
                CollectionPath: 'ServicesOffered',
                Parameters    : [
                    { $Type: 'Common.ValueListParameterInOut',       LocalDataProperty: servicesOfferedItem, ValueListProperty: 'ID' },
                    { $Type: 'Common.ValueListParameterDisplayOnly', ValueListProperty: 'workCode' },
                    { $Type: 'Common.ValueListParameterDisplayOnly', ValueListProperty: 'name' },
                    { $Type: 'Common.ValueListParameterDisplayOnly', ValueListProperty: 'standardHour' },
                    { $Type: 'Common.ValueListParameterDisplayOnly', ValueListProperty: 'currency_code' }
                ]
            }) returns Appointments.Items;
        };

    entity Appointments.Items as projection on db.Appointments.Items {
        *,
        coalesce(unitPrice, 0)
            * coalesce(case when type = 'Work' then duration else quantity end, 0)
            as totalPrice : Decimal(15, 2),
        case itemStatus
            when 'Proposed' then 2
            when 'Approved' then 3
            when 'Rejected' then 1
            else 0
        end as itemStatusCriticality : Integer,
        virtual null as quantityFieldControl : Integer,
        virtual null as durationFieldControl : Integer,
        virtual null as typeFieldControl     : Integer,
        virtual null as priceFieldControl    : Integer,
        virtual null as confirmFieldControl  : Integer,
        virtual null as canApproveItem      : Boolean,
        virtual null as canRejectItem       : Boolean
    }
    actions {
        @cds.odata.bindingparameter.name: '_it'
        action approveItem() returns Appointments.Items;
        @cds.odata.bindingparameter.name: '_it'
        action rejectItem()  returns Appointments.Items;
    };

    @(restrict: [
        { grant: 'READ', to: 'Mechanic' },
        { grant: 'getAvailableSubstitutes', to: 'Mechanic' },
        { grant: ['READ','CREATE','UPDATE'], to: 'Manager' }
    ])
    @odata.draft.enabled
    @cds.redirection.target
    entity Stocks          as projection on db.Stocks
        actions {
            function getAvailableSubstitutes() returns many Stocks;
        };

    @readonly
    @(restrict: [
        { grant: 'READ', to: ['Mechanic','Manager'] }
    ])
    @cds.redirection.target: false
    entity OriginalStocks  as projection on db.Stocks where type = 'Original';

    @(restrict: [
        { grant: 'READ', to: 'Mechanic' },
        { grant: ['READ','CREATE','UPDATE'], to: 'Manager' }
    ])
    @odata.draft.enabled
    entity ServicesOffered as projection on db.OfferedServices;

    @readonly
    entity AppointmentStatuses as projection on db.AppointmentStatuses;

    @readonly
    entity AppointmentItemStatuses as projection on db.AppointmentItemStatuses;

    @(restrict: [
        { grant: 'READ', to: ['Mechanic','Manager'] }
    ])
    entity MasterLogs      as projection on db.MasterLogs;

}

annotate RepairService.Stocks          with { modifiedAt @odata.etag; };
annotate RepairService.ServicesOffered with { modifiedAt @odata.etag; };

annotate RepairService.Appointments with actions {
    addPart @(
        Common.SideEffects : {
            TargetProperties : [ '_it/totalAmount', '_it/estimatedAmount', '_it/partsPercentage' ],
            TargetEntities   : [ '_it/items' ]
        }
    );
    addWork @(
        Common.SideEffects : {
            TargetProperties : [ '_it/totalAmount', '_it/estimatedAmount', '_it/partsPercentage' ],
            TargetEntities   : [ '_it/items' ]
        }
    );
};
