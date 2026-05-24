using com.sap.autorepair as db  from '../db/schema';

service RepairService {

    @odata.draft.enabled 
    entity Appointments as projection on db.Appointments {
        *,

        case status
            when 'New'       then 5
            when 'Pending'   then 2
            when 'Approved' then 3
            when 'In Progress' then 0
            when 'Closed' then 3
            else 0
        end as statusCriticality : Integer,

        1000.00 as estimatedAmount : Decimal(15,2), 

        50 as partsPercentage : Integer

    } actions {
        action applyStandardMaintenance() returns Appointments;
    };

    entity Stocks as projection on db.Stocks
        actions {
            function getAvailableSubstitutes() returns many Stocks;
    };

    entity ServicesOffered as projection on db.OfferedServices;

    entity MasterLogs as projection on db.MasterLogs;

}