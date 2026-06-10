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

    private static final String STOCK_BRAKE_PAD_ORIGINAL = "8f4d1b20-6a5e-4d7c-9b34-12f0a0011001";
    private static final String SERVICE_STD_MAINT        = "5b91d7c3-8a42-4f6e-9c01-12f0a0012001";

    private static final String STOCKS_URL           = "/odata/v4/RepairService/Stocks";
    private static final String SERVICES_URL         = "/odata/v4/RepairService/ServicesOffered";

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

    // ========================================================================
    // Stocks security: Client = no access, Mechanic = READ only, Manager = CRU
    // ========================================================================

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientCannotReadStocks() throws Exception {
        mockMvc.perform(get(STOCKS_URL))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientCannotCreateStock() throws Exception {
        mockMvc.perform(post(STOCKS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleNo\":\"X\",\"name\":\"X\",\"type\":\"Original\"}"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void mechanicCanReadStocks() throws Exception {
        mockMvc.perform(get(STOCKS_URL + "?$filter=IsActiveEntity eq true"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").isArray());
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void mechanicCannotCreateStock() throws Exception {
        mockMvc.perform(post(STOCKS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleNo\":\"X\",\"name\":\"X\",\"type\":\"Original\"}"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void mechanicCannotDraftEditStock() throws Exception {
        mockMvc.perform(post(STOCKS_URL + "(ID=" + STOCK_BRAKE_PAD_ORIGINAL + ",IsActiveEntity=true)/RepairService.draftEdit")
                .header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void managerCanReadStocks() throws Exception {
        mockMvc.perform(get(STOCKS_URL + "?$filter=IsActiveEntity eq true"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").isArray());
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void managerCanDraftEditAndPatchStock() throws Exception {
        String editUrl  = STOCKS_URL + "(ID=" + STOCK_BRAKE_PAD_ORIGINAL + ",IsActiveEntity=true)/RepairService.draftEdit";
        String draftUrl = STOCKS_URL + "(ID=" + STOCK_BRAKE_PAD_ORIGINAL + ",IsActiveEntity=false)";

        mockMvc.perform(post(editUrl)
                .header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(patch(draftUrl)
                .header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"quantity\": 99.00 }"))
                .andDo(print())
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void managerCannotDeleteStock() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(STOCKS_URL + "(ID=" + STOCK_BRAKE_PAD_ORIGINAL + ",IsActiveEntity=true)")
                .header("If-Match", "*"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    // ================================================================================
    // ServicesOffered security: Client = no access, Mechanic = READ only, Manager = CRU
    // ================================================================================

    @Test
    @WithMockUser(username = "client1", roles = "Client")
    public void clientCannotReadServicesOffered() throws Exception {
        mockMvc.perform(get(SERVICES_URL))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void mechanicCanReadServicesOffered() throws Exception {
        mockMvc.perform(get(SERVICES_URL + "?$filter=IsActiveEntity eq true"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").isArray());
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void mechanicCannotCreateServiceOffered() throws Exception {
        mockMvc.perform(post(SERVICES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workCode\":\"X\",\"name\":\"X\"}"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void managerCanDraftEditAndPatchServiceOffered() throws Exception {
        String editUrl  = SERVICES_URL + "(ID=" + SERVICE_STD_MAINT + ",IsActiveEntity=true)/RepairService.draftEdit";
        String draftUrl = SERVICES_URL + "(ID=" + SERVICE_STD_MAINT + ",IsActiveEntity=false)";

        mockMvc.perform(post(editUrl)
                .header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().is2xxSuccessful());

        mockMvc.perform(patch(draftUrl)
                .header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"standardHour\": 75.00 }"))
                .andDo(print())
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void managerCannotDeleteServiceOffered() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(SERVICES_URL + "(ID=" + SERVICE_STD_MAINT + ",IsActiveEntity=true)")
                .header("If-Match", "*"))
                .andDo(print())
                .andExpect(status().isForbidden());
    }
}
