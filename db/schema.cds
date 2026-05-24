namespace com.sap.autorepair;

using { cuid, managed, Currency } from '@sap/cds/common';

entity Appointments : cuid, managed {
    @mandatory
    appointmentNo : String(20);

    @mandatory
    carNumber : String(15);
    
    
    vin : String(17); @assert.format : '^[A-Z0-9]{17}$'
    
    brand : String(20);
    
    @assert.range: ['2018-10-31', '2019-01-15']
    productionYear : Integer;

    description: String(1000);
    status: Status default 'New';

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
    
    quantity : Decimal(10, 2); 
    price : Decimal(15, 2);
    currency: Currency;
    
    duration : String(50); 

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
    
    standardHour : Decimal(10, 2);
    
    price : Decimal(15, 2);
    currency: Currency;
}

type Status : String enum {
    NEW = 'New';
    PENDING = 'Pending';
    APPROVED = 'Approved';
    INPROGRESS = 'In Progress';
    CLOSED = 'Closed';
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
    duration       : String(50);
    price          : Decimal(15, 2);
    currency       : Currency;
    createdFromPos : Integer;
}


