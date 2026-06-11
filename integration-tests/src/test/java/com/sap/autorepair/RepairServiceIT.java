package com.sap.autorepair;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
       private static final String APPOINTMENT_TIRE_SERVICE_ID = "7c8d9e10-2f3a-4b5c-8d7e-9f0a1b2c1004";
       private static final String APPOINTMENT_INSPECTION_ID = "3a4b5c6d-7e8f-4a9b-bc8d-9e0f1a2b1005";
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
               .andExpect(jsonPath("$.value", hasSize(5)))
               .andExpect(jsonPath("$.value[0].appointmentNo").exists())
                      .andExpect(jsonPath("$.value[?(@.carNumber=='ABC-9876')]").exists())
               .andExpect(jsonPath("$.value[?(@.appointmentNo=='APT-1001')].estimatedAmount").exists())
               .andExpect(jsonPath("$.value[?(@.appointmentNo=='APT-1001')].partsPercentage").value(67))
               .andExpect(jsonPath("$.value[?(@.appointmentNo=='APT-1002')].partsPercentage").value(100))
               .andExpect(jsonPath("$.value[?(@.appointmentNo=='APT-1003')].partsPercentage").value(0))
               .andExpect(jsonPath("$.value[0].statusCriticality").exists());
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void testUpdateAppointmentItemTriggersTotalSumHandler() throws Exception {
              var draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
              var draftActivateUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=false)/RepairService.draftActivate";
              var draftItemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_INSPECTION_ID + ",pos=10,IsActiveEntity=false)";
              var parentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=true)";

        var updatePayload = "{ \"duration\": 2 }";

        mockMvc.perform(get(parentUrl))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalAmount").value(100.00));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(patch(draftItemUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content(updatePayload))
               .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftActivateUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get(parentUrl))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalAmount").value(200.00));
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void testDeleteAppointmentItemTriggersRecalculation() throws Exception {
              String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
              String draftActivateUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=false)/RepairService.draftActivate";
              String draftItemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_INSPECTION_ID + ",pos=10,IsActiveEntity=false)";
              String parentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=true)";

        mockMvc.perform(get(parentUrl))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalAmount").value(100.00));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(delete(draftItemUrl))
               .andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftActivateUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get(parentUrl))
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
    @WithMockUser(username = "client1", roles = "Client")
    public void testRejectingAllItemsCancelsAppointment() throws Exception {
        String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_BRAKE_JOB_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
        String itemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_BRAKE_JOB_ID + ",pos=10,IsActiveEntity=false)/RepairService.rejectItem";
        String draftActivateUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_BRAKE_JOB_ID + ",IsActiveEntity=false)/draftActivate";
        String parentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_BRAKE_JOB_ID + ",IsActiveEntity=true)";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(itemUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andDo(print())
               .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftActivateUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get(parentUrl))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status_code").value("Cancelled"));
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void testApproveItemAdvancesParentStatus() throws Exception {
        String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
        String itemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_OIL_SERVICE_ID + ",pos=20,IsActiveEntity=false)/RepairService.approveItem";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(itemUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.itemStatus_code").value("Approved"));
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void testApprovingAllItemsAdvancesAppointment() throws Exception {
        String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
        String approveWorkUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_OIL_SERVICE_ID + ",pos=10,IsActiveEntity=false)/RepairService.approveItem";
        String approvePartUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_OIL_SERVICE_ID + ",pos=20,IsActiveEntity=false)/RepairService.approveItem";
        String draftActivateUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=false)/draftActivate";
        String parentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(approveWorkUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(approvePartUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftActivateUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get(parentUrl))
               .andExpect(jsonPath("$.status_code").value("In Progress"));
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void testGetAvailableSubstitutesFunction() throws Exception {
              String functionUrl = "/odata/v4/RepairService/Stocks(" + STOCK_BRAKE_PAD_ORIGINAL_ID + ")/getAvailableSubstitutes()";

        mockMvc.perform(get(functionUrl).header("If-Match", "*"))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value").isArray())
               .andExpect(jsonPath("$.value[0].ID").value(STOCK_BRAKE_PAD_ANALOG_ID));
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void testCompletionCreatesWorkItemMasterLogs() throws Exception {
              String draftEditUrl   = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
              String approveWorkUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_OIL_SERVICE_ID + ",pos=10,IsActiveEntity=false)/RepairService.approveItem";
              String approvePartUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_OIL_SERVICE_ID + ",pos=20,IsActiveEntity=false)/RepairService.approveItem";
              String draftActivateUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=false)/draftActivate";
              String mechDraftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
              String completeUrl   = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=false)/RepairService.complete";
              String masterLogsUrl = "/odata/v4/RepairService/MasterLogs?$filter=appointment_ID eq " + APPOINTMENT_OIL_SERVICE_ID;

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(approveWorkUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(approvePartUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftActivateUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(mechDraftEditUrl)
               .with(user("bob").roles("Mechanic"))
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(completeUrl)
               .with(user("bob").roles("Mechanic"))
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status_code").value("Completed"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftActivateUrl)
               .with(user("bob").roles("Mechanic"))
               .contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get(masterLogsUrl).with(user("alice").roles("Manager")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value").isArray())
               .andExpect(jsonPath("$.value[0].appointmentNo").value("APT-1001"))
               .andExpect(jsonPath("$.value[0].workDescription").value("Engine oil replacement labor"))
               .andExpect(jsonPath("$.value[0].createdFromPos").value(10));
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
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
    @WithMockUser(username = "client1", roles = "Client")
    public void testApproveItemInDraftPropagatesToActiveOnSave() throws Exception {

        String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
        String approveWorkDraftUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_OIL_SERVICE_ID + ",pos=10,IsActiveEntity=false)/RepairService.approveItem";
        String approvePartDraftUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_OIL_SERVICE_ID + ",pos=20,IsActiveEntity=false)/RepairService.approveItem";
        String draftActivateUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=false)/draftActivate";
        String activeParentUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_OIL_SERVICE_ID + ",IsActiveEntity=true)";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftEditUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().is2xxSuccessful())
               .andExpect(jsonPath("$.IsActiveEntity").value(false));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(approveWorkDraftUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.itemStatus_code").value("Approved"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(approvePartDraftUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.itemStatus_code").value("Approved"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftActivateUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andDo(print())
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get(activeParentUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status_code").value("In Progress"));

        String activeItemsUrl = "/odata/v4/RepairService/Appointments_Items?$filter=parent_ID eq "
                + APPOINTMENT_OIL_SERVICE_ID + " and IsActiveEntity eq true";
        mockMvc.perform(get(activeItemsUrl))
               .andDo(print())
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value[?(@.pos==10)].itemStatus_code").value("Approved"))
               .andExpect(jsonPath("$.value[?(@.pos==20)].itemStatus_code").value("Approved"));
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void testApplyStandardMaintenanceRejectedOutsideInspection() throws Exception {
        String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_TIRE_SERVICE_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
        String actionUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_TIRE_SERVICE_ID + ",IsActiveEntity=false)/applyStandardMaintenance";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftEditUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(actionUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andDo(print())
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.message").value(
                       "Standard Maintenance can only be applied while the appointment is under inspection."));
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void testApplyStandardMaintenanceAddsThreeItemsToDraft() throws Exception {
              String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_TIRE_SERVICE_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
              String startInspectionUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_TIRE_SERVICE_ID + ",IsActiveEntity=false)/RepairService.startInspection";
              String draftActivateUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_TIRE_SERVICE_ID + ",IsActiveEntity=false)/RepairService.draftActivate";
              String actionUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_TIRE_SERVICE_ID + ",IsActiveEntity=false)/applyStandardMaintenance";
              String itemsUrl  = "/odata/v4/RepairService/Appointments_Items?$filter=parent_ID eq " + APPOINTMENT_TIRE_SERVICE_ID + " and IsActiveEntity eq false";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftEditUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().is2xxSuccessful())
               .andExpect(jsonPath("$.ID").value(APPOINTMENT_TIRE_SERVICE_ID))
               .andExpect(jsonPath("$.IsActiveEntity").value(false));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(startInspectionUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(draftActivateUrl)
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().is2xxSuccessful());

        String mechDraftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPOINTMENT_TIRE_SERVICE_ID + ",IsActiveEntity=true)/RepairService.draftEdit";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(mechDraftEditUrl)
               .with(user("bob").roles("Mechanic"))
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
               .post(actionUrl)
               .with(user("bob").roles("Mechanic"))
               .contentType(MediaType.APPLICATION_JSON)
               .content("{}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.ID").value(APPOINTMENT_TIRE_SERVICE_ID))
               .andExpect(jsonPath("$.IsActiveEntity").value(false));

        mockMvc.perform(get(itemsUrl).with(user("bob").roles("Mechanic")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.value", hasSize(3)))
               .andExpect(jsonPath("$.value[?(@.type=='Work')].description").value("Standard Oil and Filter Replacement"))
               .andExpect(jsonPath("$.value[?(@.type=='Part' && @.description=='Premium 5W-30 Synthetic Engine Oil')].quantity").value(4))
               .andExpect(jsonPath("$.value[?(@.type=='Part' && @.description=='Oil Filter Cartridge')].quantity").value(1));
    }
}
