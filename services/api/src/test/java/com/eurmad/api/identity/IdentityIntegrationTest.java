package com.eurmad.api.identity;

import static com.eurmad.api.security.TestOidcIssuer.CUSTOMER_A_SUB;
import static com.eurmad.api.security.TestOidcIssuer.CUSTOMER_B_SUB;
import static com.eurmad.api.security.TestOidcIssuer.UNPROVISIONED_SUB;
import static org.assertj.core.api.Assertions.assertThat;

import com.eurmad.api.error.ApiProblemException;
import com.eurmad.api.security.Ownership;
import com.eurmad.api.security.TestOidcIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** End-to-end sub→customer mapping and BOLA protection against PostgreSQL and synthetic fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(IdentityIntegrationTest.BeneficiaryProbeController.class)
@Sql("/db/testdata/FX-004__synthetic_identities.sql")
class IdentityIntegrationTest {

    private static final String CUSTOMER_A_ID = "00000000-0000-4000-8000-0000000000a1";
    private static final String CUSTOMER_B_ID = "00000000-0000-4000-8000-0000000000b1";
    private static final String BENEFICIARY_OF_A = "00000000-0000-4000-8000-00000000a001";

    private static final TestOidcIssuer ISSUER = new TestOidcIssuer();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("fintech.security.oidc.issuer", () -> TestOidcIssuer.ISSUER);
        registry.add("fintech.security.oidc.jwk-set-uri", ISSUER::jwkSetUri);
        registry.add("fintech.security.oidc.audience", () -> TestOidcIssuer.AUDIENCE);
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    @Autowired private TestRestTemplate rest;

    @Test
    void syntheticSubjectsMapToTheirOwnCustomer() {
        ResponseEntity<JsonNode> a = get("/v1/me", ISSUER.token(CUSTOMER_A_SUB));
        ResponseEntity<JsonNode> b = get("/v1/me", ISSUER.token(CUSTOMER_B_SUB));

        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.getBody().path("id").asText()).isEqualTo(CUSTOMER_A_ID);
        assertThat(a.getBody().path("residencyCountry").asText()).isEqualTo("ES");
        assertThat(a.getBody().path("kycStatus").asText()).isEqualTo("APPROVED");
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getBody().path("id").asText()).isEqualTo(CUSTOMER_B_ID);
        assertThat(b.getBody().path("residencyCountry").asText()).isEqualTo("MA");
    }

    @Test
    void subjectMatchIsExactAndCaseSensitive() {
        assertThat(get("/v1/me", ISSUER.token(CUSTOMER_A_SUB.toUpperCase())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/me", ISSUER.token(CUSTOMER_A_SUB + " ")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unprovisionedSubjectIsNotFound() {
        ResponseEntity<JsonNode> response = get("/v1/me", ISSUER.token(UNPROVISIONED_SUB));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void subjectBoundToAnotherIssuerIsNotResolved() {
        // Row exists with this subject but was bound under a previous issuer.
        ResponseEntity<JsonNode> response =
                get("/v1/me", ISSUER.token("test:fx-004:synthetic-customer-previous-issuer"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().toString()).doesNotContain("00000000-0000-4000-8000-0000000000c1");
    }

    @Test
    @Sql("/db/testdata/V1__synthetic_customer.sql")
    void legacyRowWithoutIssuerIsNotResolved() {
        // Pre-FX-004 rows have no oidc_issuer and fail closed until explicitly migrated.
        ResponseEntity<JsonNode> response = get("/v1/me", ISSUER.token("test:fx-002:synthetic-customer-001"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void noTokenIsUnauthorized() {
        ResponseEntity<JsonNode> response = get("/v1/me", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void ownerReadsOwnBeneficiary() {
        ResponseEntity<JsonNode> response = get("/v1/test/beneficiaries/" + BENEFICIARY_OF_A, ISSUER.token(CUSTOMER_A_SUB));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("maskedDestination").asText()).isEqualTo("****0001");
    }

    @Test
    void crossAccountBeneficiaryReadIsRejected() {
        ResponseEntity<JsonNode> response =
                get("/v1/test/beneficiaries/" + BENEFICIARY_OF_A, ISSUER.token(CUSTOMER_B_SUB));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().toString()).doesNotContain("Synthetic Beneficiary A", "****0001");
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    /**
     * Test-only probe showing the required ownership pattern: SQL scoped by the caller's customer
     * ID, plus the service-level guard.
     */
    @TestComponent
    @RestController
    static class BeneficiaryProbeController {
        private final JdbcTemplate jdbc;

        BeneficiaryProbeController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/v1/test/beneficiaries/{id}")
        Map<String, String> read(@CurrentCustomer AuthenticatedCustomer caller, @PathVariable UUID id) {
            Map<String, Object> row = jdbc.query(
                            "SELECT customer_id, masked_destination FROM beneficiaries"
                                    + " WHERE id = ? AND customer_id = ? AND deleted_at IS NULL",
                            (rs, n) -> Map.<String, Object>of(
                                    "customerId", rs.getObject("customer_id", UUID.class),
                                    "maskedDestination", rs.getString("masked_destination")),
                            id, caller.id())
                    .stream().findFirst().orElseThrow(ApiProblemException::notFound);
            Ownership.requireOwner(caller, (UUID) row.get("customerId"));
            return Map.of("maskedDestination", (String) row.get("maskedDestination"));
        }
    }
}
