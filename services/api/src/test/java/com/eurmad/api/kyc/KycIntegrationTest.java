package com.eurmad.api.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.eurmad.api.identity.AuthenticatedCustomer;
import com.eurmad.api.identity.EligibleCustomer;
import com.eurmad.api.kyc.VerificationDecision.Outcome;
import com.eurmad.api.security.TestOidcIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** FX-005 KYC sandbox flow, eligibility gate and no-document storage against PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(KycIntegrationTest.EligibleOperationProbe.class)
class KycIntegrationTest {

    private static final String PROBE_PATH = "/v1/test/eligible-operation";
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
        // Pin the default outcome so an inherited KYC_SANDBOX_OUTCOME cannot change expectations;
        // other outcomes are exercised per test through the provider spy.
        registry.add("fintech.kyc.sandbox.outcome", () -> "APPROVED");
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private SandboxIdentityVerificationProvider provider;

    /** Synthetic customer with a fresh subject; returns its subject. */
    private String customer(String kycStatus, String status) {
        String subject = "test:fx-005:synthetic-" + UUID.randomUUID();
        jdbc.update("INSERT INTO customers (oidc_issuer, oidc_subject, residency_country, kyc_status, status)"
                + " VALUES (?, ?, 'ES', ?, ?)", TestOidcIssuer.ISSUER, subject, kycStatus, status);
        return subject;
    }

    private UUID customerId(String subject) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE oidc_subject = ?", UUID.class, subject);
    }

    private String storedKycStatus(String subject) {
        return jdbc.queryForObject("SELECT kyc_status FROM customers WHERE oidc_subject = ?", String.class, subject);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String subject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.setBearerAuth(ISSUER.token(subject));
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> startSession(String subject) {
        return call(HttpMethod.POST, "/v1/kyc/sessions", subject, null);
    }

    private HttpStatus probe(String subject) {
        return HttpStatus.valueOf(call(HttpMethod.POST, PROBE_PATH, subject, null).getStatusCode().value());
    }

    @Test
    void statusIsReadFromOwnCustomerInSimulationMode() {
        String subject = customer("NOT_STARTED", "ACTIVE");

        ResponseEntity<JsonNode> response = call(HttpMethod.GET, "/v1/kyc/status", subject, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(response.getBody().path("mode").asText()).isEqualTo("SIMULATION");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void sandboxApprovalMakesCustomerEligibleAndIsAudited() {
        String subject = customer("NOT_STARTED", "ACTIVE");
        assertThat(probe(subject)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        ResponseEntity<JsonNode> session = startSession(subject);

        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(session.getBody().path("status").asText()).isEqualTo("APPROVED");
        assertThat(session.getBody().path("mode").asText()).isEqualTo("SIMULATION");
        assertThat(session.getBody().has("mockVerificationUrl")).isFalse();
        UUID caseId = UUID.fromString(session.getBody().path("id").asText());
        String correlationId = session.getHeaders().getFirst("X-Correlation-Id");
        assertThat(UUID.fromString(correlationId)).isNotNull();

        assertThat(storedKycStatus(subject)).isEqualTo("APPROVED");
        Map<String, Object> kycCase = jdbc.queryForMap("SELECT * FROM kyc_cases WHERE id = ?", caseId);
        assertThat(kycCase.get("customer_id")).isEqualTo(customerId(subject));
        assertThat(kycCase.get("provider")).isEqualTo("SANDBOX");
        assertThat(kycCase.get("provider_case_ref")).isEqualTo("sandbox-kyc:" + caseId);
        assertThat(kycCase.get("decided_at")).isNotNull();

        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT actor_type, actor_ref, action, safe_metadata::text AS meta FROM audit_events"
                        + " WHERE resource_type = 'KYC_CASE' AND resource_id = ? ORDER BY action DESC",
                caseId.toString());
        assertThat(audit).extracting(r -> r.get("action"))
                .containsExactly("KYC_VERIFICATION_STARTED", "KYC_DECISION_RECORDED");
        assertThat(audit.get(0).get("actor_type")).isEqualTo("CUSTOMER");
        assertThat(audit.get(0).get("actor_ref")).isEqualTo(customerId(subject).toString());
        assertThat((String) audit.get(0).get("meta")).contains("\"previousStatus\": \"NOT_STARTED\"");
        assertThat(audit.get(1).get("actor_type")).isEqualTo("PROVIDER");
        assertThat((String) audit.get(1).get("meta")).contains("\"nextStatus\": \"APPROVED\"");
        assertThat(audit).allSatisfy(r -> assertThat((String) r.get("meta"))
                .contains("\"correlationId\": \"" + correlationId + "\""));

        assertThat(call(HttpMethod.GET, "/v1/kyc/status", subject, null).getBody().path("status").asText())
                .isEqualTo("APPROVED");
        assertThat(probe(subject)).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void onlyApprovedActiveCustomersMaySubmitEligibleOperations() {
        assertThat(probe(customer("APPROVED", "ACTIVE"))).isEqualTo(HttpStatus.NO_CONTENT);
        for (String kyc : List.of("NOT_STARTED", "PENDING", "REJECTED", "EXPIRED")) {
            ResponseEntity<JsonNode> response = call(HttpMethod.POST, PROBE_PATH, customer(kyc, "ACTIVE"), null);
            assertThat(response.getStatusCode()).as(kyc).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().path("correlationId").asText()).isNotBlank();
        }
        assertThat(probe(customer("APPROVED", "SUSPENDED"))).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(probe(customer("APPROVED", "CLOSED"))).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(probe(null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(probe(TestOidcIssuer.UNPROVISIONED_SUB)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sandboxRejectionIsFinalAndIneligible() {
        doReturn(Outcome.REJECTED).when(provider).configuredOutcome();
        String subject = customer("NOT_STARTED", "ACTIVE");

        ResponseEntity<JsonNode> session = startSession(subject);

        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(session.getBody().path("status").asText()).isEqualTo("REJECTED");
        assertThat(storedKycStatus(subject)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT decision_reason_code FROM kyc_cases WHERE customer_id = ?",
                String.class, customerId(subject))).isEqualTo("SANDBOX_SIMULATED_REJECTION");
        assertThat(startSession(subject).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(probe(subject)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void sandboxPendingStaysUndecidedAndBlocksAnotherSession() {
        doReturn(Outcome.PENDING).when(provider).configuredOutcome();
        String subject = customer("NOT_STARTED", "ACTIVE");

        ResponseEntity<JsonNode> session = startSession(subject);

        assertThat(session.getBody().path("status").asText()).isEqualTo("PENDING");
        assertThat(storedKycStatus(subject)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM kyc_cases WHERE customer_id = ? AND decided_at IS NULL",
                customerId(subject))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE resource_id = ?",
                session.getBody().path("id").asText())).isEqualTo(1);
        assertThat(startSession(subject).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(probe(subject)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void expiredVerificationMayBeRepeated() {
        String subject = customer("EXPIRED", "ACTIVE");
        assertThat(startSession(subject).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storedKycStatus(subject)).isEqualTo("APPROVED");
    }

    @Test
    void disallowedTransitionsAreConflictsAndLeaveNoTrace() {
        for (String kyc : List.of("APPROVED", "PENDING", "REJECTED")) {
            String subject = customer(kyc, "ACTIVE");
            ResponseEntity<JsonNode> response = startSession(subject);
            assertThat(response.getStatusCode()).as(kyc).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(storedKycStatus(subject)).isEqualTo(kyc);
            assertThat(count("SELECT count(*) FROM kyc_cases WHERE customer_id = ?", customerId(subject)))
                    .isZero();
        }
    }

    @Test
    void suspendedOrClosedCustomersCannotStartVerification() {
        for (String status : List.of("SUSPENDED", "CLOSED")) {
            String subject = customer("NOT_STARTED", status);
            assertThat(startSession(subject).getStatusCode()).as(status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(storedKycStatus(subject)).isEqualTo("NOT_STARTED");
        }
    }

    @Test
    void providerFailureRollsBackEverything() {
        doThrow(new IllegalStateException("synthetic-provider-outage")).when(provider).startVerification(any());
        String subject = customer("NOT_STARTED", "ACTIVE");

        ResponseEntity<JsonNode> response = startSession(subject);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().toString()).doesNotContain("synthetic-provider-outage");
        assertThat(storedKycStatus(subject)).isEqualTo("NOT_STARTED");
        assertThat(count("SELECT count(*) FROM kyc_cases WHERE customer_id = ?", customerId(subject))).isZero();
        assertThat(count("SELECT count(*) FROM audit_events WHERE actor_ref = ?", customerId(subject).toString()))
                .isZero();
    }

    @Test
    void concurrentSessionStartsCreateExactlyOneCase() throws Exception {
        String subject = customer("NOT_STARTED", "ACTIVE");
        int attempts = 12;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return HttpStatus.valueOf(startSession(subject).getStatusCode().value());
                }));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == HttpStatus.CONFLICT).hasSize(attempts - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM kyc_cases WHERE customer_id = ?", customerId(subject))).isEqualTo(1);
    }

    @Test
    void submittedDocumentDataIsRejectedAndNeverStored() {
        String subject = customer("NOT_STARTED", "ACTIVE");
        Map<String, String> smuggled = Map.of(
                "documentImage", "U1lOVEhFVElDLURPQ1VNRU5ULUJZVEVT",
                "passportNumber", "SYNTHETIC-X0000000");

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/v1/kyc/sessions", subject, smuggled);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().toString()).doesNotContain("U1lOVEhFVElDLURPQ1VNRU5ULUJZVEVT", "SYNTHETIC-X0000000");
        assertThat(storedKycStatus(subject)).isEqualTo("NOT_STARTED");
        assertThat(count("SELECT count(*) FROM kyc_cases WHERE customer_id = ?", customerId(subject))).isZero();
        String stored = jdbc.queryForObject(
                "SELECT coalesce(string_agg(k::text, ''), '') FROM kyc_cases k WHERE customer_id = ?",
                String.class, customerId(subject))
                + jdbc.queryForObject("SELECT coalesce(string_agg(a::text, ''), '') FROM audit_events a",
                        String.class);
        assertThat(stored).doesNotContain("U1lOVEhFVElDLURPQ1VNRU5ULUJZVEVT", "SYNTHETIC-X0000000");
    }

    @Test
    void kycCaseSchemaHasNoDocumentColumnsAndOnlySandboxProvider() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'kyc_cases'", String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "customer_id", "provider", "provider_case_ref", "status", "decision_reason_code",
                "created_at", "decided_at");

        UUID customerId = customerId(customer("NOT_STARTED", "ACTIVE"));
        assertThat(insertCase(customerId, "LIVE_PROVIDER")).isFalse();
        assertThat(insertCase(customerId, "SANDBOX")).isTrue();
        assertThat(insertCase(customerId, "SANDBOX")).as("second pending case").isFalse();
    }

    private boolean insertCase(UUID customerId, String providerName) {
        try {
            jdbc.update("INSERT INTO kyc_cases (customer_id, provider, provider_case_ref, status)"
                    + " VALUES (?, ?, ?, 'PENDING')", customerId, providerName, UUID.randomUUID().toString());
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return false;
        }
    }

    @Test
    void unauthenticatedCallsAreRejected() {
        assertThat(call(HttpMethod.GET, "/v1/kyc/status", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(startSession(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(startSession(TestOidcIssuer.UNPROVISIONED_SUB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Test-only stand-in for a future eligible operation (quote, transfer, offer). */
    @TestComponent
    @RestController
    static class EligibleOperationProbe {
        @PostMapping(PROBE_PATH)
        ResponseEntity<Void> submit(@EligibleCustomer AuthenticatedCustomer customer) {
            return ResponseEntity.noContent().build();
        }
    }
}
