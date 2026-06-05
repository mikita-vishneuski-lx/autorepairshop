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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class SecurityIT {

    private static final String APPT_OIL_CLIENT1   = "9f1c2d68-1a6b-4e9a-8db5-4f0c3b2a1001";
    private static final String APPT_BRAKE_CLIENT1 = "2d7a0f54-8d18-4b64-9c4f-6a2dcb551002";
    private static final String APPT_DIAG_CLIENT2  = "5b3c7a91-9f2e-4c18-a7b9-1d6e3f441003";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientSeesOnlyOwnAppointments() throws Exception {
        mockMvc.perform(get("/odata/v4/RepairService/Appointments?$filter=IsActiveEntity eq true"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value", hasSize(2)))
                .andExpect(jsonPath("$.value[?(@.ID=='" + APPT_OIL_CLIENT1 + "')]").exists())
                .andExpect(jsonPath("$.value[?(@.ID=='" + APPT_BRAKE_CLIENT1 + "')]").exists())
                .andExpect(jsonPath("$.value[?(@.ID=='" + APPT_DIAG_CLIENT2 + "')]").doesNotExist());
    }

    @Test
    @WithMockUser(username = "client2", roles = "Client")
    public void clientCannotReadOtherClientsAppointment() throws Exception {

        mockMvc.perform(get("/odata/v4/RepairService/Appointments(ID=" + APPT_OIL_CLIENT1 + ",IsActiveEntity=true)"))
                .andDo(print())
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientCannotPatchVin() throws Exception {
        mockMvc.perform(patch("/odata/v4/RepairService/Appointments(ID=" + APPT_OIL_CLIENT1 + ",IsActiveEntity=true)")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"vin\": \"AAAAAAAAAAAAAAAAA\" }"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientCanInvokeApproveItemOnOwnAppointment() throws Exception {
        String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPT_BRAKE_CLIENT1 + ",IsActiveEntity=true)/RepairService.draftEdit";
        String itemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPT_BRAKE_CLIENT1 + ",pos=10,IsActiveEntity=false)/RepairService.approveItem";

        mockMvc.perform(post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post(itemUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemStatus").value("Approved"));
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientCannotDeleteAppointment() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/odata/v4/RepairService/Appointments(ID=" + APPT_BRAKE_CLIENT1 + ",IsActiveEntity=true)"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientCannotApplyStandardMaintenance() throws Exception {
        String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPT_BRAKE_CLIENT1 + ",IsActiveEntity=true)/RepairService.draftEdit";
        String actionUrl = "/odata/v4/RepairService/Appointments(ID=" + APPT_BRAKE_CLIENT1 + ",IsActiveEntity=false)/RepairService.applyStandardMaintenance";

        mockMvc.perform(post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post(actionUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientCannotStartInspection() throws Exception {
        String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPT_BRAKE_CLIENT1 + ",IsActiveEntity=true)/RepairService.draftEdit";
        String actionUrl = "/odata/v4/RepairService/Appointments(ID=" + APPT_BRAKE_CLIENT1 + ",IsActiveEntity=false)/RepairService.startInspection";

        mockMvc.perform(post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post(actionUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void mechanicCannotStartInspection() throws Exception {
        String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPT_BRAKE_CLIENT1 + ",IsActiveEntity=true)/RepairService.draftEdit";
        String actionUrl = "/odata/v4/RepairService/Appointments(ID=" + APPT_BRAKE_CLIENT1 + ",IsActiveEntity=false)/RepairService.startInspection";

        mockMvc.perform(post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post(actionUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientSeesCanFlagsConsistentWithRole() throws Exception {
        mockMvc.perform(get("/odata/v4/RepairService/Appointments(ID=" + APPT_BRAKE_CLIENT1 + ",IsActiveEntity=true)"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canApplyStandardMaintenance").value(false))
                .andExpect(jsonPath("$.canStartInspection").value(false))
                .andExpect(jsonPath("$.canClose").value(false));
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void mechanicCannotPatchHeaderVin() throws Exception {
        mockMvc.perform(patch("/odata/v4/RepairService/Appointments(ID=" + APPT_OIL_CLIENT1 + ",IsActiveEntity=true)")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"vin\": \"AAAAAAAAAAAAAAAAA\" }"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void mechanicCannotPatchItemPrice() throws Exception {
        String draftEditUrl = "/odata/v4/RepairService/Appointments(ID=" + APPT_OIL_CLIENT1 + ",IsActiveEntity=true)/RepairService.draftEdit";
        String draftItemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPT_OIL_CLIENT1 + ",pos=10,IsActiveEntity=false)";

        mockMvc.perform(post(draftEditUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(patch(draftItemUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"quantity\": 99 }"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void managerCannotPatchHeader() throws Exception {

        mockMvc.perform(patch("/odata/v4/RepairService/Appointments(ID=" + APPT_BRAKE_CLIENT1 + ",IsActiveEntity=true)")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"vin\": \"WAU00000000000099\" }"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientCannotUpdateVinOncePastCreatedStatus() throws Exception {

        mockMvc.perform(patch("/odata/v4/RepairService/Appointments(ID=" + APPT_OIL_CLIENT1 + ",IsActiveEntity=true)")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"vin\": \"WBA00000000000099\" }"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }
}
