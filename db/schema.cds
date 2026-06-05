namespace com.sap.autorepair;

using { cuid, managed, Currency, sap.common.CodeList } from '@sap/cds/common';

entity Appointments : cuid, managed {
    @mandatory
    appointmentNo : String(20);

    @mandatory
    carNumber : String(15);

    @assert.format : '^([A-Z0-9]{17})?$'
    vin : String(17);

    brand : String(20);

    @assert.range: [1900, 2100]
    productionYear : Integer;

    description: String(1000);
    status: String(40) default 'Created';

    estimatedAmount: Decimal(15, 2);
    totalAmount: Decimal(15, 2);
    currency: Currency;

    items : Composition of many Appointments.Items on items.parent = $self;
}

entity Appointments.Items {
    key parent : Association to Appointments;
    key pos : Integer;

    @mandatory
    type : ItemType;

    description : String(255);

    unitPrice : Decimal(15, 2);

    quantity : Decimal(10, 2);

    duration : Decimal(10, 2);

    currency : Currency;

    confirmedByClient : Boolean default false;
    itemStatus : String(20) default 'Proposed';

    stockItem : Association to Stocks;
    servicesOfferedItem : Association to OfferedServices;
}

entity Stocks : cuid, managed {

    @mandatory
    articleNo : String(50);

    @mandatory
    name : String (255);

    brand : String (50);

    @mandatory
    type : StockType;

    quantity: Decimal(10, 2);
    price: Decimal(15, 2);
    currency: Currency;

    original : Association to Stocks;
}

entity OfferedServices : cuid, managed {

    @mandatory
    workCode : String(50);

    @mandatory
    name: String(255);

    standardHour : Decimal(15, 2);
    currency: Currency;
}

entity AppointmentStatuses : CodeList {
    key code : String enum {
        CREATED              = 'Created';
        INSPECTION           = 'Inspection';
        WAITING_FOR_APPROVAL = 'Waiting for approval';
        IN_PROGRESS          = 'In Progress';
        COMPLETED            = 'Completed';
        CLOSED               = 'Closed';
        CANCELLED            = 'Cancelled';
    };
}

entity AppointmentItemStatuses : CodeList {
    key code : String enum {
        PROPOSED = 'Proposed';
        APPROVED = 'Approved';
        REJECTED = 'Rejected';
    };
}

type ItemType : String enum {
    WORK = 'Work';
    PART = 'Part';
}

type StockType : String enum {
    Original;
    Analog;
}

entity MasterLogs : cuid, managed {
    appointment    : Association to Appointments;
    appointmentNo  : String(20);
    workDescription : String(255);
    duration       : Decimal(10, 2);
    price          : Decimal(15, 2);
    currency       : Currency;
    createdFromPos : Integer;
}
