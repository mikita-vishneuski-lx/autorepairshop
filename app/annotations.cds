using RepairService from '../srv/repair-service';

annotate RepairService.Appointments.Items with {
    pos               @UI.Hidden;
    type              @title: 'Type'                @Core.Computed: true;
    description       @title: 'Description'         @Core.Computed: true;
    unitPrice         @title: 'Price / Hourly Rate' @Core.Computed: true
                      @Measures.ISOCurrency: currency_code;
    quantity          @title: 'Quantity'  @Common.FieldControl: quantityFieldControl;
    duration          @title: 'Hours'     @Common.FieldControl: durationFieldControl;
    totalPrice        @title: 'Total'     @Core.Computed: true
                      @Measures.ISOCurrency: currency_code;
    currency         @Core.Computed: true;
    confirmedByClient @UI.Hidden;
    canApproveItem    @UI.Hidden;
    canRejectItem     @UI.Hidden;
    itemStatus       @title: 'Decision'
                      @Common.FieldControl: #ReadOnly;
    quantityFieldControl  @UI.Hidden;
    durationFieldControl  @UI.Hidden;
    typeFieldControl      @UI.Hidden;
    priceFieldControl     @UI.Hidden;
    confirmFieldControl   @UI.Hidden;
    itemStatusCriticality @UI.Hidden;
};

annotate RepairService.Appointments.Items with @(
    UI.CreateHidden: true,
    Common.SideEffects #qty: {
        SourceProperties: [quantity],
        TargetProperties: ['totalPrice'],
        TargetEntities  : [parent]
    },
    Common.SideEffects #dur: {
        SourceProperties: [duration],
        TargetProperties: ['totalPrice'],
        TargetEntities  : [parent]
    },
    Common.SideEffects #itemStatusCrit: {
        SourceProperties: [itemStatus_code],
        TargetProperties: ['itemStatusCriticality']
    },
    UI.LineItem    : [
        {Value: type},
        {Value: description},
        {Value: unitPrice},
        {Value: quantity},
        {Value: duration},
        {Value: totalPrice},
        {Value: itemStatus_code,   Criticality: itemStatusCriticality},
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.approveItem',
            Label        : 'Approve',
            Inline       : true,
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canApproveItem' } } }
        },
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.rejectItem',
            Label        : 'Reject',
            Inline       : true,
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canRejectItem' } } }
        }
    ]
);

annotate RepairService.Appointments.Items with actions {
    approveItem
    @Core.OperationAvailable: { $edmJson: { $Path: '_it/canApproveItem' } }
    @Common.SideEffects: {
        TargetProperties: ['_it/itemStatus_code', '_it/itemStatusCriticality',
                           '_it/parent/status_code', '_it/parent/statusCriticality'],
        TargetEntities  : ['_it', '_it/parent', '_it/parent/items']
    };

    rejectItem
    @Core.OperationAvailable: { $edmJson: { $Path: '_it/canRejectItem' } }
    @Common.SideEffects: {
        TargetProperties: ['_it/itemStatus_code', '_it/itemStatusCriticality',
                           '_it/parent/status_code', '_it/parent/statusCriticality'],
        TargetEntities  : ['_it', '_it/parent', '_it/parent/items']
    }
};

annotate RepairService.Appointments with @(UI: {
    Identification             : [
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.startInspection',
            Label        : 'Start Inspection',
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canStartInspection' } } }
        },
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.requestApproval',
            Label        : 'Request Approval',
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canRequestApproval' } } }
        },
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.complete',
            Label        : 'Complete',
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canComplete' } } }
        },
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.close',
            Label        : 'Close',
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canClose' } } }
        },
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.cancel',
            Label        : 'Cancel',
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canCancel' } } }
        },
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.approveAllItems',
            Label        : 'Approve All',
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canApproveAllItems' } } }
        },
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.rejectAllItems',
            Label        : 'Reject All',
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canRejectAllItems' } } }
        },
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.applyStandardMaintenance',
            Label        : 'Standard Maintenance',
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canApplyStandardMaintenance' } } }
        },
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.addPart',
            Label        : 'Add Part',
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canAddItems' } } }
        },
        {
            $Type        : 'UI.DataFieldForAction',
            Action       : 'RepairService.addWork',
            Label        : 'Add Work',
            ![@UI.Hidden]: { $edmJson: { $Not: { $Path: 'canAddItems' } } }
        }
    ],
    HeaderInfo                 : {
        TypeName      : 'Appointment',
        TypeNamePlural: 'Appointments',
        Title         : {Value: carNumber}
    },

    LineItem                   : [
        {Value: appointmentNo},
        {Value: carNumber},
        {Value: brand},
        {Value: status_code,  Criticality: statusCriticality},
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
        {Value: status_code}
    ]},

    FieldGroup #CarInfo        : {Data: [
        {Value: carNumber},
        {Value: brand},
        {Value: vin},
        {Value: productionYear}
    ]}
});

annotate RepairService.Appointments with @(
    Common.SideEffects #statusCrit: {
        SourceProperties: [status_code],
        TargetProperties: ['statusCriticality']
    }
);

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
                      @Common.FieldControl: #ReadOnly;
    estimatedAmount   @title       : 'Budget'
                      @Common.FieldControl: headerFieldControl
                      @Core.Description: 'Quoted price agreed with the client';
    totalAmount       @title       : 'Actual'
                      @Core.Computed
                      @Core.Description: 'Live total of items (parts + work)';
    statusCriticality @UI.Hidden;
    headerFieldControl @UI.Hidden;
    partsPercentage    @UI.Hidden @Core.Computed;
    canApplyStandardMaintenance @UI.Hidden;
    canStartInspection          @UI.Hidden;
    canRequestApproval          @UI.Hidden;
    canComplete                 @UI.Hidden;
    canClose                    @UI.Hidden;
    canCancel                   @UI.Hidden;
    canAddItems                 @UI.Hidden;
};

annotate RepairService.Appointments with actions {
    applyStandardMaintenance
    @Common.SideEffects: {
        TargetEntities  : [items],
        TargetProperties: [totalAmount]
    }
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canApplyStandardMaintenance' } };

    startInspection
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canStartInspection' } }
    @Common.SideEffects: { TargetProperties: ['in/status_code', 'in/statusCriticality'] };

    requestApproval
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canRequestApproval' } }
    @Common.SideEffects: { TargetProperties: ['in/status_code', 'in/statusCriticality'] };

    complete
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canComplete' } }
    @Common.SideEffects: { TargetProperties: ['in/status_code', 'in/statusCriticality'] };

    close
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canClose' } }
    @Common.SideEffects: { TargetProperties: ['in/status_code', 'in/statusCriticality'] };

    cancel
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canCancel' } }
    @Common.SideEffects: { TargetProperties: ['in/status_code', 'in/statusCriticality'] };

    addPart
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canAddItems' } }
    @Common.SideEffects: { TargetEntities: [items], TargetProperties: [totalAmount] };

    addWork
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canAddItems' } }
    @Common.SideEffects: { TargetEntities: [items], TargetProperties: [totalAmount] };

    approveAllItems
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canApproveAllItems' } }
    @Common.SideEffects: {
        TargetEntities  : ['in/items'],
        TargetProperties: ['in/status_code', 'in/statusCriticality']
    };

    rejectAllItems
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canRejectAllItems' } }
    @Common.SideEffects: {
        TargetEntities  : ['in/items'],
        TargetProperties: ['in/status_code', 'in/statusCriticality']
    }
};

annotate RepairService.Stocks with {
    ID            @UI.Hidden;
    articleNo     @title: 'Article No.';
    name          @title: 'Name';
    brand         @title: 'Brand';
    type          @title: 'Type'
                  @Core.Computed: true;
    quantity      @title: 'Stock Qty';
    price         @title: 'Price'
                  @Measures.ISOCurrency: currency_code;
    currency      @title: 'Currency';
    original      @title: 'Original Part'
                  @Common.ValueListWithFixedValues: true
                  @Common.ValueList: {
                      CollectionPath: 'Stocks',
                      Parameters    : [
                          { $Type            : 'Common.ValueListParameterInOut',
                            LocalDataProperty: original_ID,
                            ValueListProperty: 'ID' },
                          { $Type            : 'Common.ValueListParameterDisplayOnly',
                            ValueListProperty: 'articleNo' },
                          { $Type            : 'Common.ValueListParameterDisplayOnly',
                            ValueListProperty: 'name' },
                          { $Type            : 'Common.ValueListParameterDisplayOnly',
                            ValueListProperty: 'brand' },
                          { $Type            : 'Common.ValueListParameterConstant',
                            ValueListProperty: 'type',
                            Constant         : 'Original' }
                      ]
                  };
    createdAt     @title: 'Created';
    createdBy     @title: 'Created By';
    modifiedAt    @title: 'Last Changed';
    modifiedBy    @title: 'Last Changed By';
};

annotate RepairService.Stocks with @(
    Common.SideEffects #typeFromOriginal: {
        SourceProperties: [original_ID],
        TargetProperties: ['type']
    },
    UI: {
        HeaderInfo      : {
            TypeName       : 'Part',
            TypeNamePlural : 'Parts',
            Title          : { Value: name },
            Description    : { Value: articleNo }
        },

        SelectionFields : [ articleNo, name, brand, type ],

        LineItem        : [
            { Value: articleNo, Label: 'Article No.' },
            { Value: name },
            { Value: brand },
            { Value: type },
            { Value: quantity },
            { Value: price }
        ],

        Facets          : [
            { $Type : 'UI.ReferenceFacet',
              Label : 'General',
              Target: '@UI.FieldGroup#General' },
            { $Type : 'UI.ReferenceFacet',
              Label : 'Pricing & Stock',
              Target: '@UI.FieldGroup#PricingStock' },
            { $Type : 'UI.ReferenceFacet',
              Label : 'Administrative',
              Target: '@UI.FieldGroup#Admin' }
        ],

        FieldGroup #General      : { Data: [
            { Value: brand },
            { Value: original_ID, Label: 'Original Part' },
            { Value: type }
        ]},

        FieldGroup #PricingStock : { Data: [
            { Value: quantity },
            { Value: price },
            { Value: currency_code }
        ]},

        FieldGroup #Admin        : { Data: [
            { Value: createdAt },
            { Value: createdBy },
            { Value: modifiedAt },
            { Value: modifiedBy }
        ]}
    }
);

annotate RepairService.ServicesOffered with {
    ID            @UI.Hidden;
    workCode      @title: 'Work Code';
    name          @title: 'Name';
    standardHour  @title: 'Hourly Rate'
                  @Measures.ISOCurrency: currency_code;
    currency      @title: 'Currency';
    createdAt     @title: 'Created';
    createdBy     @title: 'Created By';
    modifiedAt    @title: 'Last Changed';
    modifiedBy    @title: 'Last Changed By';
};

annotate RepairService.ServicesOffered with @(UI: {
    HeaderInfo      : {
        TypeName       : 'Service',
        TypeNamePlural : 'Services',
        Title          : { Value: name },
        Description    : { Value: workCode }
    },

    SelectionFields : [ workCode, name ],

    LineItem        : [
        { Value: workCode, Label: 'Work Code' },
        { Value: name },
        { Value: standardHour }
    ],

    Facets          : [
        { $Type : 'UI.ReferenceFacet',
          Label : 'Pricing',
          Target: '@UI.FieldGroup#Pricing' },
        { $Type : 'UI.ReferenceFacet',
          Label : 'Administrative',
          Target: '@UI.FieldGroup#Admin' }
    ],

    FieldGroup #Pricing : { Data: [
        { Value: standardHour },
        { Value: currency_code }
    ]},

    FieldGroup #Admin   : { Data: [
        { Value: createdAt },
        { Value: createdBy },
        { Value: modifiedAt },
        { Value: modifiedBy }
    ]}
});
