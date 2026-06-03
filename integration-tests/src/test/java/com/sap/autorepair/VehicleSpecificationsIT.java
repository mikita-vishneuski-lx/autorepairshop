package com.sap.autorepair;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;

import customer.autorepairshop.Application;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Application.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles({ "default", "mocked" })
public class VehicleSpecificationsIT {

    private static final String ACTION_PATH = "/odata/v4/RepairService/getVehicleSpecificationsByVin";
    private static final String MOCK_ENTITY_PATH = "/odata/v4/VehicleSpecsService/VehicleSpecifications";

    private static final String KNOWN_VIN_BMW = "WBA00000000000001";
    private static final String KNOWN_VIN_AUDI = "WAU00000000000002";
    private static final String UNKNOWN_VIN = "XXX00000000000099";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private TestRestTemplate authed() {
        return restTemplate.withBasicAuth("alice", "alice");
    }

    private static HttpEntity<String> jsonBody(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    public void mockedRemoteServiceEndpointIsServedLocally() {
        ResponseEntity<JsonNode> response = authed().exchange(
                url(MOCK_ENTITY_PATH + "(vin='" + KNOWN_VIN_BMW + "')"),
                HttpMethod.GET, null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("vin").asText()).isEqualTo(KNOWN_VIN_BMW);
        assertThat(body.get("manufacturer").asText()).isEqualTo("BMW");
        assertThat(body.get("model").asText()).isEqualTo("320i");
    }

    @Test
    public void getVehicleSpecificationsByVinReturnsDataForKnownVin() {
        ResponseEntity<JsonNode> response = authed().exchange(
                url(ACTION_PATH), HttpMethod.POST,
                jsonBody("{\"vin\":\"" + KNOWN_VIN_BMW + "\"}"),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("vin").asText()).isEqualTo(KNOWN_VIN_BMW);
        assertThat(body.get("manufacturer").asText()).isEqualTo("BMW");
        assertThat(body.get("model").asText()).isEqualTo("320i");
        assertThat(body.get("engineCode").asText()).isEqualTo("B48B20");
        assertThat(body.get("enginePowerKw").asInt()).isEqualTo(135);
        assertThat(body.get("fuelType").asText()).isEqualTo("Petrol");
        assertThat(body.get("transmission").asText()).isEqualTo("Automatic");
        assertThat(body.get("driveType").asText()).isEqualTo("RWD");
        assertThat(body.get("bodyType").asText()).isEqualTo("Sedan");
        assertThat(body.get("productionYear").asInt()).isEqualTo(2018);
    }

    @Test
    public void getVehicleSpecificationsByVinReturnsDataForAnotherKnownVin() {
        ResponseEntity<JsonNode> response = authed().exchange(
                url(ACTION_PATH), HttpMethod.POST,
                jsonBody("{\"vin\":\"" + KNOWN_VIN_AUDI + "\"}"),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("vin").asText()).isEqualTo(KNOWN_VIN_AUDI);
        assertThat(body.get("manufacturer").asText()).isEqualTo("Audi");
        assertThat(body.get("model").asText()).isEqualTo("A4 40 TFSI");
        assertThat(body.get("productionYear").asInt()).isEqualTo(2020);
    }

    @Test
    public void getVehicleSpecificationsByVinReturnsNotFoundForUnknownVin() {
        ResponseEntity<JsonNode> response = authed().exchange(
                url(ACTION_PATH), HttpMethod.POST,
                jsonBody("{\"vin\":\"" + UNKNOWN_VIN + "\"}"),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("error").path("message").asText())
                .contains("Vehicle specifications not found")
                .contains(UNKNOWN_VIN);
    }

    @Test
    public void getVehicleSpecificationsByVinReturnsBadRequestForBlankVin() {
        ResponseEntity<JsonNode> response = authed().exchange(
                url(ACTION_PATH), HttpMethod.POST,
                jsonBody("{\"vin\":\"                 \"}"),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("error").path("message").asText())
                .contains("VIN must be provided");
    }
}
