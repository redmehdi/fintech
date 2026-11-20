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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ApiApplicationTests {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private FintechProperties properties;
    @Autowired private JdbcTemplate jdbc;

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

    @Test
    @Sql("/db/testdata/V1__synthetic_customer.sql")
    void flywayCreatesCoreSchemaAndSyntheticFixturesCanBeInserted() {
        Integer customerCount =
                jdbc.queryForObject(
                        "SELECT count(*) FROM customers WHERE oidc_subject = ?",
                        Integer.class,
                        "test:fx-002:synthetic-customer-001");

        assertThat(customerCount).isEqualTo(1);
    }
}
