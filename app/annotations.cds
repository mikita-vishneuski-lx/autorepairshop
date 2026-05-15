using RepairService from '../srv/repair-service';

annotate RepairService.Appointments.Items with @(
    UI: {
        LineItem: [
            { Value: pos },
            { Value: type },
            { Value: description },
            { Value: quantity },
            { Value: price }
        ]
    }
);

annotate RepairService.Appointments with @(
    UI: {
        HeaderInfo: {
            TypeName: 'Appointment',
            TypeNamePlural: 'Appointments',
            Title: { Value: carNumber }
        },

        LineItem: [
            { Value: carNumber, Title: 'Car Nubmer' },
            { Value: brand, Title: 'Brand'},
            { 
                Value: status, 
                Criticality: statusCriticality 
            },
            {
                $Type: 'UI.DataFieldForAnnotation', 
                Target: '@UI.Chart#BulletChart', 
                Label: 'Costs (Microchart)'
            }
        ],

        HeaderFacets: [
            {
                $Type: 'UI.ReferenceFacet',
                Target: '@UI.DataPoint#TotalAmount'
            },
            {
                $Type: 'UI.ReferenceFacet',
                Target: '@UI.DataPoint#PartsPercentage'
            }
        ],

        Facets: [
            {
                $Type: 'UI.ReferenceFacet',
                Label: 'Car Information',
                Target: '@UI.FieldGroup#CarInfo'
            },
            {
                $Type: 'UI.ReferenceFacet',
                Label: 'List of Work/Parts',
                Target: 'items/@UI.LineItem'
            }
        ],

        DataPoint #Costs: {
            Value: totalAmount,
            TargetValue: estimatedAmount,
            Title: 'Costs',
            Criticality: statusCriticality
        },

        Chart #BulletChart: {
            ChartType: #Bullet,
            Measures: [ totalAmount ],
            MeasureAttributes: [{
                Measure: totalAmount,
                Role: #Axis1,
                DataPoint: '@UI.DataPoint#Costs'
            }]
        },

        
        DataPoint #TotalAmount: {
            Value: totalAmount,
            Title: 'Total Amount'
        },

        
        DataPoint #PartsPercentage: {
            Value: partsPercentage,
            TargetValue: 100, 
            Visualization: #Progress,
            Title: 'Parts Share (%)'
        },

        FieldGroup #CarInfo: {
            Data: [
                { Value: carNumber },
                { Value: brand },
                { Value: vin },
                { Value: productionYear },
                { Value: description }
            ]
        }
    }
);

annotate RepairService.Appointments with {
    status            @title: 'Status';
    statusCriticality @UI.Hidden; 
};
