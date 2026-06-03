using RepairService from '../srv/repair-service';

annotate RepairService.Appointments.Items with {
    pos               @title: 'Pos.';
    type              @title: 'Type'         @Common.FieldControl: typeFieldControl;
    description       @title: 'Description'  @Common.FieldControl: rowFieldControl;
    quantity          @title: 'Quantity'     @Common.FieldControl: rowFieldControl;
    price             @title: 'Price'        @Common.FieldControl: priceFieldControl;
    duration          @title: 'Duration'     @Common.FieldControl: rowFieldControl;
    confirmedByClient @title: 'Confirmed'    @Common.FieldControl: confirmFieldControl;
    rowFieldControl     @UI.Hidden;
    typeFieldControl    @UI.Hidden;
    priceFieldControl   @UI.Hidden;
    confirmFieldControl @UI.Hidden;
};

annotate RepairService.Appointments.Items with @(UI: {LineItem: [
    {Value: pos},
    {Value: type},
    {Value: description},
    {Value: quantity},
    {Value: price},
    {Value: confirmedByClient}
]});

annotate RepairService.Appointments with @(UI: {
    Identification             : [{
        $Type : 'UI.DataFieldForAction',
        Action: 'RepairService.applyStandardMaintenance',
        Label : 'Standard Maintenance'
    }],
    HeaderInfo                 : {
        TypeName      : 'Appointment',
        TypeNamePlural: 'Appointments',
        Title         : {Value: carNumber}
    },

    LineItem                   : [
        {Value: appointmentNo},
        {Value: carNumber},
        {Value: brand},
        {Value: status,       Criticality: statusCriticality},
        {
            $Type : 'UI.DataFieldForAnnotation',
            Target: '@UI.Chart#CostsBullet',
            Label : 'Actual vs. Budget'
        }
    ],

    HeaderFacets               : [
        {
            $Type : 'UI.ReferenceFacet',
            Target: '@UI.DataPoint#TotalAmount'
        },
        {
            $Type : 'UI.ReferenceFacet',
            Target: '@UI.Chart#PartsPercentageRadial'
        }
    ],

    Facets                     : [
        {
            $Type : 'UI.ReferenceFacet',
            Label : 'General Information',
            Target: '@UI.FieldGroup#General'
        },
        {
            $Type : 'UI.ReferenceFacet',
            Label : 'Car Information',
            Target: '@UI.FieldGroup#CarInfo'
        },
        {
            $Type : 'UI.ReferenceFacet',
            Label : 'List of Work/Parts',
            Target: 'items/@UI.LineItem'
        }
    ],

    DataPoint #CostsBullet     : {
        Value        : totalAmount,
        TargetValue  : estimatedAmount,
        Visualization: #BulletChart,
        Title        : 'Actual vs. Budget',
        Criticality  : statusCriticality
    },

    Chart #CostsBullet         : {
        Title            : 'Actual vs. Budget',
        ChartType        : #Bullet,
        Measures         : [totalAmount],
        MeasureAttributes: [{
            $Type    : 'UI.ChartMeasureAttributeType',
            Measure  : totalAmount,
            Role     : #Axis1,
            DataPoint: '@UI.DataPoint#CostsBullet'
        }]
    },

    DataPoint #TotalAmount     : {
        Value      : totalAmount,
        Title      : 'Actual',
        Criticality: statusCriticality
    },

    DataPoint #PartsPercentage : {
        Value        : partsPercentage,
        TargetValue  : 100,
        Visualization: #Donut,
        Title        : 'Parts Share (%)'
    },

    Chart #PartsPercentageRadial : {
        Title            : 'Parts Share (%)',
        ChartType        : #Donut,
        Measures         : [partsPercentage],
        MeasureAttributes: [{
            $Type    : 'UI.ChartMeasureAttributeType',
            Measure  : partsPercentage,
            Role     : #Axis1,
            DataPoint: '@UI.DataPoint#PartsPercentage'
        }]
    },

    FieldGroup #General        : {Data: [
        {Value: appointmentNo},
        {Value: description},
        {Value: estimatedAmount},
        {Value: status}
    ]},

    FieldGroup #CarInfo        : {Data: [
        {Value: carNumber},
        {Value: brand},
        {Value: vin},
        {Value: productionYear}
    ]}
});

annotate RepairService.Appointments with {
    appointmentNo     @title       : 'Appointment No.'
                      @Common.FieldControl: headerFieldControl
                      @Core.Description: 'Unique number, e.g. APT-1005';
    carNumber         @title       : 'License Plate'
                      @Common.FieldControl: headerFieldControl
                      @Core.Description: 'Vehicle license plate, e.g. XYZ-1234';
    vin               @title       : 'VIN'
                      @Common.FieldControl: headerFieldControl
                      @Core.Description: '17-character Vehicle Identification Number';
    brand             @title       : 'Brand'
                      @Common.FieldControl: headerFieldControl
                      @Core.Description: 'Manufacturer, e.g. BMW, Audi';
    productionYear    @title       : 'Production Year'
                      @Common.FieldControl: headerFieldControl;
    description       @title       : 'Description'
                      @Common.FieldControl: headerFieldControl
                      @UI.MultiLineText
                      @Core.Description: 'What needs to be done (max 1000 chars)';
    status            @title       : 'Status'
                      @Common.FieldControl: statusFieldControl
                      @Common.ValueListWithFixedValues: true
                      @Common.ValueList: {
                          CollectionPath: 'AppointmentStatuses',
                          Parameters    : [
                              { $Type: 'Common.ValueListParameterInOut',
                                LocalDataProperty: status,
                                ValueListProperty: 'code' }
                          ]
                      };
    estimatedAmount   @title       : 'Budget'
                      @Common.FieldControl: headerFieldControl
                      @Core.Description: 'Quoted price agreed with the client';
    totalAmount       @title       : 'Actual'
                      @Core.Computed
                      @Core.Description: 'Live total of items (parts + work)';
    statusCriticality @UI.Hidden;
    headerFieldControl @UI.Hidden;
    statusFieldControl @UI.Hidden;
    partsPercentage    @UI.Hidden @Core.Computed;
};

annotate RepairService.Appointments with actions {
    applyStandardMaintenance
    @Common.SideEffects: {
        TargetEntities  : [items],
        TargetProperties: [totalAmount]
    }
    @Core.OperationAvailable: {$edmJson: {$Eq: [
        {$Path: 'IsActiveEntity'},
        false
    ]}}
};
