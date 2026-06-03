package com.sap.autorepair;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import customer.autorepairshop.Application;

import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class RepairServiceIT {

       private static final String APPOINTMENT_OIL_SERVICE_ID = "9f1c2d68-1a6b-4e9a-8db5-4f0c3b2a1001";
       private static final String APPOINTMENT_BRAKE_JOB_ID = "2d7a0f54-8d18-4b64-9c4f-6a2dcb551002";
       private static final String APPOINTMENT_DIAGNOSTIC_ID = "5b3c7a91-9f2e-4c18-a7b9-1d6e3f441003";
       private static final String APPOINTMENT_TIRE_SERVICE_ID = "7c8d9e10-2f3a-4b5c-8d7e-9f0a1b2c1004";
       private static final String STOCK_BRAKE_PAD_ORIGINAL_ID = "8f4d1b20-6a5e-4d7c-9b34-12f0a0011001";
       private static final String STOCK_BRAKE_PAD_ANALOG_ID = "1c92e8b7-3d44-4f6b-8a11-12f0a0011002";
       private static final String STOCK_UNKNOWN_ID = "de6f8c1a-9f44-4cc8-b511-5db1ef001999";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void testReadAppointmentsVirtualFields() throws Exception {
              mockMvc.perform(get("/odata/v4/RepairService/Appointments?$filter=IsActiveEntity eq true"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value").isArray())
               .andExpect(jsonPath("$.value", hasSize(4)))
               .andExpect(jsonPath("$.value[0].appointmentNo").exists())
                      .andExpect(jsonPath("$.value[?(@.carNumber=='ABC-9876')]").exists())
               .andExpect(jsonPath("$.value[?(@.appointmentNo=='APT-1001')].estimatedAmount").exists())
               .andExpect(jsonPath("$.value[?(@.appointmentNo=='APT-1001')].partsPercentage").value(67))
               .andExpect(jsonPath("$.value[?(@.appointmentNo=='APT-1002')].partsPercentage").value(100))
               .andExpect(jsonPath("$.value[?(@.appointmentNo=='APT-1003')].partsPercentage").value(0))
               .andExpect(jsonPath("$.value[0].statusCriticality").exists());
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void testUpdateAppointmentItemTriggersTotalSumHandler() throws Exception {
              var itemUrl   = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_OIL_SERVICE_ID + ",pos=10,IsActiveEntity=true)";
              var parentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)";
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
    @WithMockUser(username = "alice", roles = "Manager")
    public void testDeleteAppointmentItemTriggersRecalculation() throws Exception {
              String itemUrl   = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_BRAKE_JOB_ID + ",pos=10,IsActiveEntity=true)";
              String parentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_BRAKE_JOB_ID + ",IsActiveEntity=true)";

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
    @WithMockUser(username = "alice", roles = "Manager")
    public void testStockValidationPreventsOverconsumption() throws Exception {
              String itemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_OIL_SERVICE_ID + ",pos=20,IsActiveEntity=true)";
              String updatePayload = "{ \"quantity\": 9999, \"stockItem_ID\": \"" + STOCK_UNKNOWN_ID + "\" }";

        mockMvc.perform(patch(itemUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content(updatePayload))
               .andDo(print())
               .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void testInProgressTransitionRequiresApprovedTasks() throws Exception {
              String appointmentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_DIAGNOSTIC_ID + ",IsActiveEntity=true)";
        String updatePayload = "{ \"status\": \"In Progress\" }";

        mockMvc.perform(patch(appointmentUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content(updatePayload))
               .andDo(print())
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.message").value("The request cannot be set to In Progress without approved tasks in the Items list"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void testInProgressTransitionRequiresConfirmedCriticalParts() throws Exception {
              String appointmentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)";
        String updatePayload = "{ \"status\": \"In Progress\" }";

        mockMvc.perform(patch(appointmentUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content(updatePayload))
               .andDo(print())
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.message").value("The request cannot be set to In Progress until the warehouse confirms critical spare parts availability"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void testInProgressTransitionSucceedsWithApprovedTasksAndConfirmedParts() throws Exception {
        String itemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_OIL_SERVICE_ID + ",pos=20,IsActiveEntity=true)";
        String appointmentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)";

        mockMvc.perform(patch(itemUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{ \"stockItem_ID\": \"" + STOCK_BRAKE_PAD_ORIGINAL_ID + "\" }"))
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
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void testGetAvailableSubstitutesFunction() throws Exception {
              String functionUrl = "/odata/v4/RepairService/Stocks(ID=" + STOCK_BRAKE_PAD_ORIGINAL_ID + ")/getAvailableSubstitutes()";

        mockMvc.perform(get(functionUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value").isArray())
               .andExpect(jsonPath("$.value[0].ID").value(STOCK_BRAKE_PAD_ANALOG_ID));
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void testApprovalCreatesWorkItemMasterLogs() throws Exception {
              String appointmentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)";
              String masterLogsUrl  = "/odata/v4/RepairService/MasterLogs?$filter=appointment_ID eq " + APPOINTMENT_OIL_SERVICE_ID;

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
               .andExpect(jsonPath("$.value[0].workDescription").value("Engine oil replacement labor"))
               .andExpect(jsonPath("$.value[0].createdFromPos").value(10));
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void testApplyStandardMaintenanceRequiresDraftMode() throws Exception {
              String actionUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_TIRE_SERVICE_ID + ",IsActiveEntity=true)/applyStandardMaintenance";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(actionUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andDo(print())
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.message").value(
                       "Please open the appointment in Edit mode first, then apply Standard Maintenance."));
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void testApplyStandardMaintenanceAddsThreeItemsToDraft() throws Exception {
              String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_TIRE_SERVICE_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
              String actionUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_TIRE_SERVICE_ID + ",IsActiveEntity=false)/applyStandardMaintenance";
              String itemsUrl  = "/odata/v4/RepairService/Appointments_Items?$filter=parent_ID eq " + APPOINTMENT_TIRE_SERVICE_ID + " and IsActiveEntity eq false";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftEditUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andDo(print())
               .andExpect(status().is2xxSuccessful())
               .andExpect(jsonPath("$.ID").value(APPOINTMENT_TIRE_SERVICE_ID))
               .andExpect(jsonPath("$.IsActiveEntity").value(false));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(actionUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.ID").value(APPOINTMENT_TIRE_SERVICE_ID))
               .andExpect(jsonPath("$.IsActiveEntity").value(false));

        mockMvc.perform(get(itemsUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value", hasSize(3)))
               .andExpect(jsonPath("$.value[?(@.type=='Work')].description").value("Standard Oil and Filter Replacement"))
               .andExpect(jsonPath("$.value[?(@.type=='Part' && @.description=='Premium 5W-30 Synthetic Engine Oil')].quantity").value(4))
               .andExpect(jsonPath("$.value[?(@.type=='Part' && @.description=='Oil Filter Cartridge')].quantity").value(1));
    }
}
