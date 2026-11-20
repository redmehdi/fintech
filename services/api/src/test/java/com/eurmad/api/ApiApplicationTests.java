package com.eurmad.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.eurmad.api.config.FintechProperties;
import com.eurmad.api.config.OperatingMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiApplicationTests {

    @Autowired private TestRestTemplate rest;
    @Autowired private FintechProperties properties;

    @Test
    void defaultsToSimulationMode() {
        assertThat(properties.mode()).isEqualTo(OperatingMode.SIMULATION);
        assertThat(properties.liveEnabled()).isFalse();
    }

    @Test
    void healthEndpointIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void infoEndpointReportsSimulationMode() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/info", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"operatingMode\":\"SIMULATION\"")
                .contains("\"liveEnabled\":false");
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() {
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/actuator/configprops", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
