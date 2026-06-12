package com.sap.autorepair;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import customer.autorepairshop.Application;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class ConcurrencyIT {

    private static final String STOCK_BRAKE_PAD_ORIGINAL_ID = "8f4d1b20-6a5e-4d7c-9b34-12f0a0011001";
    private static final String STOCK_BUDGET_ZERO_ID        = "2e18c0d1-7b55-4e92-9c88-12f0a0011003";
    private static final String APPOINTMENT_INSPECTION_ID   = "3a4b5c6d-7e8f-4a9b-bc8d-9e0f1a2b1005";
    private static final String STOCKS_URL                  = "/odata/v4/RepairService/Stocks";
    private static final String APPTS_URL                   = "/odata/v4/RepairService/Appointments";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void stockPatchWithStaleEtagReturns412() throws Exception {
        String activeUrl = STOCKS_URL + "(ID=" + STOCK_BRAKE_PAD_ORIGINAL_ID + ",IsActiveEntity=true)";
        String draftEditUrl = activeUrl + "/RepairService.draftEdit";
        String draftUrl  = STOCKS_URL + "(ID=" + STOCK_BRAKE_PAD_ORIGINAL_ID + ",IsActiveEntity=false)";

        mockMvc.perform(post(draftEditUrl).header("If-Match", "*").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());

        MvcResult getRes = mockMvc.perform(get(draftUrl))
                .andExpect(status().isOk())
                .andReturn();
        String realEtag = getRes.getResponse().getHeader("ETag");
        org.junit.jupiter.api.Assertions.assertNotNull(realEtag, "Expected ETag header from GET, headers=" + getRes.getResponse().getHeaderNames());

        String tampered = tamperEtag(realEtag);

        mockMvc.perform(patch(draftUrl)
                        .header("If-Match", tampered)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"quantity\": 1.00 }"))
                .andExpect(status().isPreconditionFailed());
    }

    private static String tamperEtag(String etag) {
        int q1 = etag.indexOf('"');
        int q2 = etag.lastIndexOf('"');
        if (q1 < 0 || q2 <= q1) {
            return etag + "x";
        }
        String prefix = etag.substring(0, q1 + 1);
        String suffix = etag.substring(q2);
        return prefix + "2000-01-01T00:00:00.000Z" + suffix;
    }

    @Test
    @WithMockUser(username = "alice", roles = "Manager")
    public void stockPatchWithMatchingEtagSucceeds() throws Exception {
        String activeUrl = STOCKS_URL + "(ID=" + STOCK_BRAKE_PAD_ORIGINAL_ID + ",IsActiveEntity=true)";
        String draftEditUrl = activeUrl + "/RepairService.draftEdit";
        String draftUrl  = STOCKS_URL + "(ID=" + STOCK_BRAKE_PAD_ORIGINAL_ID + ",IsActiveEntity=false)";
        String activateUrl = draftUrl + "/draftActivate";

        mockMvc.perform(post(draftEditUrl).header("If-Match", "*").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());

        MvcResult getRes = mockMvc.perform(get(draftUrl))
                .andExpect(status().isOk())
                .andReturn();
        String etag = getRes.getResponse().getHeader("ETag");

        mockMvc.perform(patch(draftUrl)
                        .header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"quantity\": 7.00 }"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post(activateUrl).header("If-Match", "*").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void addPartDecrementsStockOnActivate() throws Exception {
        String editUrl       = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
        String addPartUrl    = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=false)/RepairService.addPart";
        String activateUrl   = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=false)/draftActivate";
        String stockUrl      = STOCKS_URL + "(ID=" + STOCK_BRAKE_PAD_ORIGINAL_ID + ",IsActiveEntity=true)";

        mockMvc.perform(get(stockUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(10.00));

        mockMvc.perform(post(editUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post(addPartUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"stockItem\": \"" + STOCK_BRAKE_PAD_ORIGINAL_ID + "\" }"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post(activateUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get(stockUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(9.00));
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void addPartFromZeroStockFailsOnActivate() throws Exception {
        String editUrl     = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
        String addPartUrl  = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=false)/RepairService.addPart";
        String activateUrl = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=false)/draftActivate";
        String stockUrl    = STOCKS_URL + "(ID=" + STOCK_BUDGET_ZERO_ID + ",IsActiveEntity=true)";

        mockMvc.perform(get(stockUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(0.00));

        mockMvc.perform(post(editUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post(addPartUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"stockItem\": \"" + STOCK_BUDGET_ZERO_ID + "\" }"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post(activateUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get(stockUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(0.00));
    }

    @Test
    @WithMockUser(username = "bob", roles = "Mechanic")
    public void deleteDraftItemReleasesReservation() throws Exception {
        String editUrl     = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
        String addPartUrl  = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=false)/RepairService.addPart";
        String activateUrl = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=false)/draftActivate";
        String edit2Url    = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=true)/RepairService.draftEdit";
        String stockUrl    = STOCKS_URL + "(ID=" + STOCK_BRAKE_PAD_ORIGINAL_ID + ",IsActiveEntity=true)";
        String itemsUrl    = APPTS_URL + "(ID=" + APPOINTMENT_INSPECTION_ID + ",IsActiveEntity=false)/items";

        mockMvc.perform(get(stockUrl))
                .andExpect(jsonPath("$.quantity").value(10.00));

        mockMvc.perform(post(editUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(post(addPartUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"stockItem\": \"" + STOCK_BRAKE_PAD_ORIGINAL_ID + "\" }"))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(post(activateUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get(stockUrl))
                .andExpect(jsonPath("$.quantity").value(9.00));

        mockMvc.perform(post(edit2Url).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());

        MvcResult items = mockMvc.perform(get(itemsUrl + "?$filter=type eq 'Part' and stockItem_ID eq " + STOCK_BRAKE_PAD_ORIGINAL_ID))
                .andExpect(status().isOk())
                .andReturn();
        String body = items.getResponse().getContentAsString();
        Integer pos = extractFirstPos(body);
        org.junit.jupiter.api.Assertions.assertNotNull(pos, "Expected a Part draft item with stock " + STOCK_BRAKE_PAD_ORIGINAL_ID + " in: " + body);

        String draftItemUrl = "/odata/v4/RepairService/Appointments_Items(parent_ID=" + APPOINTMENT_INSPECTION_ID + ",pos=" + pos + ",IsActiveEntity=false)";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(draftItemUrl))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(activateUrl).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get(stockUrl))
                .andExpect(jsonPath("$.quantity").value(10.00));
    }

    private static Integer extractFirstPos(String json) {
        int idx = json.indexOf("\"pos\":");
        if (idx < 0) return null;
        int start = idx + 6;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return null;
        return Integer.parseInt(json.substring(start, end));
    }
}
