package com.sap.autorepair;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import customer.autorepairshop.Application;

import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
public class RepairServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser("authenticated")
    public void testReadAppointmentsVirtualFields() throws Exception {
        mockMvc.perform(get("/odata/v4/RepairService/Appointments"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value").isArray())
               .andExpect(jsonPath("$.value", hasSize(4)))
               .andExpect(jsonPath("$.value[0].appointmentNo").exists())
               .andExpect(jsonPath("$.value[1].carNumber", is("ABC-9876")))
               .andExpect(jsonPath("$.value[0].estimatedAmount").value(1000.00))
               .andExpect(jsonPath("$.value[0].partsPercentage").value(50))
               .andExpect(jsonPath("$.value[0].statusCriticality").exists());
    }

    @Test
    @WithMockUser("authenticated")
    public void testUpdateAppointmentItemTriggersTotalSumHandler() throws Exception {
        var itemUrl   = "/odata/v4/RepairService/Appointments_Items(parent_ID=11111111-1111-1111-1111-111111111111,pos=10,IsActiveEntity=true)";
        var parentUrl = "/odata/v4/RepairService/Appointments(ID=11111111-1111-1111-1111-111111111111,IsActiveEntity=true)";
        var updatePayload = "{ \"price\": 150.00 }";

        mockMvc.perform(get(parentUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalAmount").value(150.00));

        mockMvc.perform(patch(itemUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content(updatePayload))
               .andDo(print())
               .andExpect(status().isOk());

        mockMvc.perform(get(parentUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalAmount").value(250.00));
    }

    @Test
    @WithMockUser("authenticated")
    public void testDeleteAppointmentItemTriggersRecalculation() throws Exception {
        String itemUrl   = "/odata/v4/RepairService/Appointments_Items(parent_ID=22222222-2222-2222-2222-222222222222,pos=10,IsActiveEntity=true)";
        String parentUrl = "/odata/v4/RepairService/Appointments(ID=22222222-2222-2222-2222-222222222222,IsActiveEntity=true)";

        mockMvc.perform(get(parentUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalAmount").value(450.00));

        mockMvc.perform(delete(itemUrl))
               .andDo(print())
               .andExpect(status().isNoContent());

        mockMvc.perform(get(parentUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalAmount").value(0.00));
    }

    @Test
    @WithMockUser("authenticated")
    public void testStockValidationPreventsOverconsumption() throws Exception {
        String itemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=11111111-1111-1111-1111-111111111111,pos=20,IsActiveEntity=true)";
        String updatePayload = "{ \"quantity\": 9999, \"stockItem_ID\": \"11111111-1111-1111-1111-111111111111\" }";

        mockMvc.perform(patch(itemUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content(updatePayload))
               .andDo(print())
               .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    @WithMockUser("authenticated")
    public void testInProgressTransitionRequiresApprovedTasks() throws Exception {
        String appointmentUrl = "/odata/v4/RepairService/Appointments(ID=33333333-3333-3333-3333-333333333333,IsActiveEntity=true)";
        String updatePayload = "{ \"status\": \"In Progress\" }";

        mockMvc.perform(patch(appointmentUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content(updatePayload))
               .andDo(print())
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.message").value("The request cannot be set to In Progress without approved tasks in the Items list"));
    }

    @Test
    @WithMockUser("authenticated")
    public void testInProgressTransitionRequiresConfirmedCriticalParts() throws Exception {
        String appointmentUrl = "/odata/v4/RepairService/Appointments(ID=11111111-1111-1111-1111-111111111111,IsActiveEntity=true)";
        String updatePayload = "{ \"status\": \"In Progress\" }";

        mockMvc.perform(patch(appointmentUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content(updatePayload))
               .andDo(print())
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.message").value("The request cannot be set to In Progress until the warehouse confirms critical spare parts availability"));
    }

    @Test
    @WithMockUser("authenticated")
    public void testInProgressTransitionSucceedsWithApprovedTasksAndConfirmedParts() throws Exception {
        String itemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=11111111-1111-1111-1111-111111111111,pos=20,IsActiveEntity=true)";
        String appointmentUrl = "/odata/v4/RepairService/Appointments(ID=11111111-1111-1111-1111-111111111111,IsActiveEntity=true)";

        mockMvc.perform(patch(itemUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{ \"stockItem_ID\": \"11111111-1111-1111-1111-111111111112\" }"))
               .andDo(print())
               .andExpect(status().isOk());

        mockMvc.perform(patch(appointmentUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{ \"status\": \"In Progress\" }"))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("In Progress"));
    }

    @Test
    @WithMockUser("privileged")
    public void testGetAvailableSubstitutesFunction() throws Exception {
        String functionUrl = "/odata/v4/RepairService/Stocks(ID=11111111-1111-1111-1111-111111111112)/getAvailableSubstitutes()";

        mockMvc.perform(get(functionUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value").isArray())
               .andExpect(jsonPath("$.value[0].ID").value("22222222-2222-2222-2222-222222222222"));
    }

    @Test
    @WithMockUser("authenticated")
    public void testApprovalCreatesWorkItemMasterLogs() throws Exception {
        String appointmentUrl = "/odata/v4/RepairService/Appointments(ID=11111111-1111-1111-1111-111111111111,IsActiveEntity=true)";
        String masterLogsUrl  = "/odata/v4/RepairService/MasterLogs?$filter=appointment_ID eq 11111111-1111-1111-1111-111111111111";

        mockMvc.perform(patch(appointmentUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{ \"status\": \"Approved\" }"))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("Approved"));

        mockMvc.perform(get(masterLogsUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value").isArray())
               .andExpect(jsonPath("$.value[0].appointmentNo").value("APT-1001"))
               .andExpect(jsonPath("$.value[0].workDescription").value("Oil change labor"))
               .andExpect(jsonPath("$.value[0].createdFromPos").value(10));
    }

    @Test
    @WithMockUser("authenticated")
    public void testApplyStandardMaintenanceAddsThreeItems() throws Exception {
        String actionUrl = "/odata/v4/RepairService/Appointments(ID=44444444-4444-4444-4444-444444444444,IsActiveEntity=true)/applyStandardMaintenance";
        String itemsUrl  = "/odata/v4/RepairService/Appointments_Items?$filter=parent_ID eq 44444444-4444-4444-4444-444444444444 and IsActiveEntity eq true";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(actionUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.ID").value("44444444-4444-4444-4444-444444444444"));

        mockMvc.perform(get(itemsUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value", hasSize(3)))
               .andExpect(jsonPath("$.value[?(@.type=='Work')].description").value("Oil Change Service"))
               .andExpect(jsonPath("$.value[?(@.type=='Part' && @.description=='Engine Oil')].quantity").value(4))
               .andExpect(jsonPath("$.value[?(@.type=='Part' && @.description=='Oil Filter')].quantity").value(1));
    }
}
