package com.eurmad.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.eurmad.api.config.FintechProperties;
import com.eurmad.api.config.OperatingMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(ApiApplicationTests.ErrorTestEndpoint.class)
class ApiApplicationTests {

    private static final String ERROR_TEST_PATH = "/v1/test/error";
    private static final String SYNTHETIC_EXCEPTION_MESSAGE = "synthetic-private-failure";

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
    void missingApiRouteReturnsProblemEnvelope() {
        assertProblem(rest.getForEntity("/v1/missing", JsonNode.class), HttpStatus.NOT_FOUND, "Not Found");
    }

    @Test
    void unhandledExceptionReturnsClientSafeProblem() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = rest.postForEntity(
                ERROR_TEST_PATH, new HttpEntity<>("{}", headers), JsonNode.class);

        assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred.");
        assertThat(response.getBody().toString()).doesNotContain(SYNTHETIC_EXCEPTION_MESSAGE);
    }

    @Test
    void unsupportedMethodReturnsProblemAndPreservesAllow() {
        ResponseEntity<JsonNode> response = rest.getForEntity(ERROR_TEST_PATH, JsonNode.class);

        assertProblem(response, HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.POST);
    }

    @Test
    void unsupportedMediaTypeReturnsProblem() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<JsonNode> response = rest.postForEntity(
                ERROR_TEST_PATH, new HttpEntity<>("synthetic text", headers), JsonNode.class);

        assertProblem(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type");
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

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("code").asText()).isEqualTo(status.name());
        assertThat(body.path("message").asText()).isEqualTo(message);
        String correlationId = body.path("correlationId").asText();
        assertThat(UUID.fromString(correlationId).toString()).isEqualTo(correlationId);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(correlationId);
    }

    @TestComponent
    @RestController
    static class ErrorTestEndpoint {
        @PostMapping(path = ERROR_TEST_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
        void fail() {
            throw new IllegalStateException(SYNTHETIC_EXCEPTION_MESSAGE);
        }
    }
}
