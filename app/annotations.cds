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
    currency_code     @Core.Computed: true;
    confirmedByClient @UI.Hidden;
    canApproveItem    @UI.Hidden;
    canRejectItem     @UI.Hidden;
    itemStatus        @title: 'Decision'
                      @Common.FieldControl: #ReadOnly
                      @Common.ValueListWithFixedValues: true
                      @Common.ValueList: {
                          CollectionPath: 'AppointmentItemStatuses',
                          Parameters    : [
                              { $Type: 'Common.ValueListParameterInOut',
                                LocalDataProperty: itemStatus,
                                ValueListProperty: 'code' }
                          ]
                      };
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
    UI.LineItem    : [
        {Value: type},
        {Value: description},
        {Value: unitPrice},
        {Value: quantity},
        {Value: duration},
        {Value: totalPrice},
        {Value: itemStatus,        Criticality: itemStatusCriticality},
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
        TargetProperties: ['_it/itemStatus', '_it/itemStatusCriticality'],
        TargetEntities  : ['_it/parent', '_it/parent/items']
    };

    rejectItem
    @Core.OperationAvailable: { $edmJson: { $Path: '_it/canRejectItem' } }
    @Common.SideEffects: {
        TargetProperties: ['_it/itemStatus', '_it/itemStatusCriticality'],
        TargetEntities  : ['_it/parent', '_it/parent/items']
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
                      @Common.FieldControl: #ReadOnly
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
    @Common.SideEffects: { TargetProperties: ['in/status', 'in/statusCriticality'] };

    requestApproval
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canRequestApproval' } }
    @Common.SideEffects: { TargetProperties: ['in/status', 'in/statusCriticality'] };

    complete
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canComplete' } }
    @Common.SideEffects: { TargetProperties: ['in/status', 'in/statusCriticality'] };

    close
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canClose' } }
    @Common.SideEffects: { TargetProperties: ['in/status', 'in/statusCriticality'] };

    cancel
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canCancel' } }
    @Common.SideEffects: { TargetProperties: ['in/status', 'in/statusCriticality'] };

    addPart
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canAddItems' } }
    @Common.SideEffects: { TargetEntities: [items], TargetProperties: [totalAmount] };

    addWork
    @Core.OperationAvailable: { $edmJson: { $Path: 'in/canAddItems' } }
    @Common.SideEffects: { TargetEntities: [items], TargetProperties: [totalAmount] }
};
