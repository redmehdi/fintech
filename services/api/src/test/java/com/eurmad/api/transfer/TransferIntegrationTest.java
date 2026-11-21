package com.eurmad.api.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eurmad.api.RestTestSupport;
import com.eurmad.api.error.ApiProblemException;
import com.eurmad.api.security.TestOidcIssuer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * FX-008 transfer creation, idempotency, concurrency, quote consumption, ownership, lifecycle
 * events and the database-enforced state machine against PostgreSQL. All data is synthetic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Testcontainers(disabledWithoutDocker = true)
@Import(TransferIntegrationTest.ClockConfig.class)
class TransferIntegrationTest {

    private static final String PATH = "/v1/transfers";
    private static final Duration QUOTE_TTL = Duration.ofMinutes(10);
    private static final TestOidcIssuer ISSUER = new TestOidcIssuer();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired private RestTestClient rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private TransferService transfers;
    @Autowired private TransactionTemplate transactions;

    private Instant start;

    @BeforeEach
    void resetClock() {
        start = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(start);
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private String customer(String kycStatus, String status) {
        String subject = "test:fx-008:synthetic-" + UUID.randomUUID();
        jdbc.update("INSERT INTO customers (oidc_issuer, oidc_subject, residency_country, kyc_status, status)"
                + " VALUES (?, ?, 'ES', ?, ?)", TestOidcIssuer.ISSUER, subject, kycStatus, status);
        return subject;
    }

    private String customer() {
        return customer("APPROVED", "ACTIVE");
    }

    private UUID customerId(String subject) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE oidc_subject = ?", UUID.class, subject);
    }

    /** Synthetic sandbox beneficiary; resolves to no real destination. */
    private UUID beneficiary(String subject, String country) {
        String token = "MA".equals(country) ? "sim-token:ma-bank-mab1" : "sim-token:es-bank-esb1";
        return jdbc.queryForObject("INSERT INTO beneficiaries (customer_id, display_name, country, payout_method,"
                        + " destination_token_ref, masked_destination) VALUES (?, 'Synthetic Recipient', ?,"
                        + " 'BANK_ACCOUNT', ?, ?) RETURNING id",
                UUID.class, customerId(subject), country, token, "****" + token.substring(token.length() - 4));
    }

    private UUID quote(String subject, String source, String target, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceCurrency", source);
        body.put("targetCurrency", target);
        body.put("sourceAmountMinor", amount);
        body.put("purpose", "FAMILY_SUPPORT");
        ResponseEntity<JsonNode> response = post("/v1/quotes", subject, newKey(), body, MediaType.APPLICATION_JSON);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().path("id").stringValue());
    }

    private UUID eurToMadQuote(String subject) {
        return quote(subject, "EUR", "MAD", "10000");
    }

    private static Map<String, Object> body(Object quoteId, Object beneficiaryId, Object purpose) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quoteId", quoteId == null ? null : quoteId.toString());
        body.put("beneficiaryId", beneficiaryId == null ? null : beneficiaryId.toString());
        body.put("purpose", purpose);
        return body;
    }

    private static String newKey() {
        return "idem-" + UUID.randomUUID();
    }

    private ResponseEntity<JsonNode> post(String path, String subject, String key, Object body, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.setBearerAuth(ISSUER.token(subject));
        }
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        headers.setContentType(type);
        return RestTestSupport.exchange(rest, path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> create(String subject, String key, Object body) {
        return post(PATH, subject, key, body, MediaType.APPLICATION_JSON);
    }

    private ResponseEntity<JsonNode> get(String subject, String path) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.setBearerAuth(ISSUER.token(subject));
        }
        return RestTestSupport.exchange(rest, path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private UUID createdTransfer(String subject) {
        ResponseEntity<JsonNode> response = create(subject, newKey(),
                body(eurToMadQuote(subject), beneficiary(subject, "MA"), "FAMILY_SUPPORT"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().path("id").stringValue());
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int transferCount(String subject) {
        return count("SELECT count(*) FROM transfers WHERE customer_id = ?", customerId(subject));
    }

    private String quoteStatus(UUID quoteId) {
        return jdbc.queryForObject("SELECT status FROM fx_quotes WHERE id = ?", String.class, quoteId);
    }

    /** A decision based on {@code expected} and the transfer's version as observed right now. */
    private HttpStatus transition(UUID id, TransferStatus expected, TransferStatus target) {
        long version = transfers.observe(id).map(TransferService.Observed::version).orElse(0L);
        return transition(id, new TransferService.Observed(expected, version), target);
    }

    private HttpStatus transition(UUID id, TransferService.Observed expected, TransferStatus target) {
        try {
            transactions.executeWithoutResult(tx -> transfers.transition(id, expected, target,
                    TransferService.ActorType.PROVIDER, "simulator", "SIM_TEST", UUID.randomUUID()));
            return HttpStatus.OK;
        } catch (ApiProblemException e) {
            return e.status();
        }
    }

    // ---- creation -------------------------------------------------------------------------------

    @Test
    void createsSimulatedTransferConsumingTheQuoteWithLifecycleEvents() {
        String subject = customer();
        UUID owner = customerId(subject);
        UUID quoteId = eurToMadQuote(subject);
        UUID beneficiaryId = beneficiary(subject, "MA");
        String key = newKey();

        ResponseEntity<JsonNode> response = create(subject, key, body(quoteId, beneficiaryId, "FAMILY_SUPPORT"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        UUID correlationId = UUID.fromString(response.getHeaders().getFirst("X-Correlation-Id"));
        JsonNode transfer = response.getBody();
        assertThat(transfer.propertyNames()).containsExactlyInAnyOrder("id", "quoteId", "beneficiaryId", "purpose",
                "status", "mode", "createdAt", "updatedAt");
        assertThat(transfer.path("quoteId").stringValue()).isEqualTo(quoteId.toString());
        assertThat(transfer.path("beneficiaryId").stringValue()).isEqualTo(beneficiaryId.toString());
        assertThat(transfer.path("purpose").stringValue()).isEqualTo("FAMILY_SUPPORT");
        assertThat(transfer.path("status").stringValue()).isEqualTo("AWAITING_FUNDING");
        assertThat(transfer.path("mode").stringValue()).isEqualTo("SIMULATION");
        assertThat(Instant.parse(transfer.path("createdAt").stringValue())).isEqualTo(start);
        assertThat(Instant.parse(transfer.path("updatedAt").stringValue())).isEqualTo(start);

        UUID id = UUID.fromString(transfer.path("id").stringValue());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM transfers WHERE id = ?", id);
        assertThat(row).containsEntry("customer_id", owner).containsEntry("quote_id", quoteId)
                .containsEntry("status", "AWAITING_FUNDING").containsEntry("provider", "SIMULATOR")
                .containsEntry("version", 1L);
        assertThat(row.get("provider_transfer_ref")).isNull();
        assertThat(quoteStatus(quoteId)).isEqualTo("CONSUMED");

        // Lifecycle events: request, then eligibility-passed transition, both correlated.
        List<Map<String, Object>> events = jdbc.queryForList("SELECT previous_status, next_status, actor_type,"
                + " actor_ref, reason_code, correlation_id FROM transaction_events WHERE resource_kind = 'TRANSFER'"
                + " AND resource_id = ? ORDER BY recorded_at, next_status = 'CREATED' DESC", id);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("next_status", "CREATED").containsEntry("actor_type", "CUSTOMER")
                .containsEntry("actor_ref", owner.toString()).containsEntry("correlation_id", correlationId);
        assertThat(events.get(0).get("previous_status")).isNull();
        assertThat(events.get(1)).containsEntry("previous_status", "CREATED")
                .containsEntry("next_status", "AWAITING_FUNDING").containsEntry("actor_type", "SYSTEM")
                .containsEntry("reason_code", "ELIGIBILITY_PASSED").containsEntry("correlation_id", correlationId);

        assertThat(jdbc.queryForObject("SELECT resource_id FROM idempotency_keys WHERE customer_id = ? AND"
                + " operation = 'CREATE_TRANSFER' AND idempotency_key = ?", UUID.class, owner, key)).isEqualTo(id);
        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_ref, action, resource_type, safe_metadata::text AS m"
                + " FROM audit_events WHERE resource_id = ?", id.toString());
        assertThat(audit).containsEntry("actor_ref", owner.toString()).containsEntry("action", "TRANSFER_CREATED")
                .containsEntry("resource_type", "TRANSFER");
        assertThat((String) audit.get("m")).contains(correlationId.toString(), "SIMULATION");
        // Enum/label metadata only: no amounts, beneficiary or destination data.
        assertThat(jdbc.queryForList("SELECT jsonb_object_keys(safe_metadata) FROM audit_events WHERE resource_id = ?",
                String.class, id.toString()))
                .containsExactlyInAnyOrder("sourceCurrency", "targetCurrency", "purpose", "status", "mode",
                        "correlationId");
    }

    @Test
    void madToEurTransferIsSimulationOnlyAndPaidOutInSpain() {
        String subject = customer();
        UUID quoteId = quote(subject, "MAD", "EUR", "100000");

        ResponseEntity<JsonNode> toMorocco = create(subject, newKey(),
                body(quoteId, beneficiary(subject, "MA"), "PERSONAL_TRANSFER"));
        assertThat(toMorocco.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> toSpain = create(subject, newKey(),
                body(quoteId, beneficiary(subject, "ES"), "PERSONAL_TRANSFER"));
        assertThat(toSpain.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(toSpain.getBody().path("mode").stringValue()).isEqualTo("SIMULATION");
        assertThat(toSpain.getBody().path("status").stringValue()).isEqualTo("AWAITING_FUNDING");
    }

    @Test
    void identicalReplayReturnsTheSameTransferAndDifferentPayloadConflicts() {
        String subject = customer();
        UUID quoteId = eurToMadQuote(subject);
        UUID beneficiaryId = beneficiary(subject, "MA");
        String key = newKey();

        ResponseEntity<JsonNode> first = create(subject, key, body(quoteId, beneficiaryId, "FAMILY_SUPPORT"));
        // UUID case is not significant: the same request.
        ResponseEntity<JsonNode> replay = create(subject, key,
                body(quoteId.toString().toUpperCase(), beneficiaryId, "FAMILY_SUPPORT"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        // Same key, different payload: 409 and nothing new is written.
        assertThat(create(subject, key, body(quoteId, beneficiaryId, "EDUCATION")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(create(subject, key, body(eurToMadQuote(subject), beneficiaryId, "FAMILY_SUPPORT"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(create(subject, key, body(quoteId, beneficiary(subject, "MA"), "FAMILY_SUPPORT"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(transferCount(subject)).isEqualTo(1);

        // A consumed quote cannot back a second transfer under a new key.
        assertThat(create(subject, newKey(), body(quoteId, beneficiaryId, "FAMILY_SUPPORT")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(transferCount(subject)).isEqualTo(1);
    }

    @Test
    void replayReturnsTheOriginalTransferInItsCurrentStateEvenAfterQuoteExpiry() {
        String subject = customer();
        UUID quoteId = eurToMadQuote(subject);
        UUID beneficiaryId = beneficiary(subject, "MA");
        String key = newKey();
        ResponseEntity<JsonNode> first = create(subject, key, body(quoteId, beneficiaryId, "FAMILY_SUPPORT"));
        UUID id = UUID.fromString(first.getBody().path("id").stringValue());

        clock.set(start.plus(QUOTE_TTL).plusSeconds(60));
        assertThat(transition(id, TransferStatus.AWAITING_FUNDING, TransferStatus.CANCELLED)).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> replay = create(subject, key, body(quoteId, beneficiaryId, "FAMILY_SUPPORT"));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("id").stringValue()).isEqualTo(id.toString());
        assertThat(replay.getBody().path("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(transferCount(subject)).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalSubmissionsCreateOneTransfer() throws Exception {
        String subject = customer();
        Map<String, Object> request = body(eurToMadQuote(subject), beneficiary(subject, "MA"), "FAMILY_SUPPORT");
        String key = newKey();
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return create(subject, key, request);
                }));
            }
            go.countDown();
            Set<String> ids = new HashSet<>();
            for (Future<ResponseEntity<JsonNode>> result : results) {
                ResponseEntity<JsonNode> response = result.get(60, TimeUnit.SECONDS);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                ids.add(response.getBody().path("id").stringValue());
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(transferCount(subject)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE customer_id = ? AND operation = 'CREATE_TRANSFER'",
                customerId(subject))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transaction_events WHERE resource_kind = 'TRANSFER' AND resource_id IN"
                + " (SELECT id FROM transfers WHERE customer_id = ?)", customerId(subject))).isEqualTo(2);
    }

    @Test
    void concurrentSubmissionsWithDifferentKeysConsumeTheQuoteOnce() throws Exception {
        String subject = customer();
        Map<String, Object> request = body(eurToMadQuote(subject), beneficiary(subject, "MA"), "FAMILY_SUPPORT");
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return create(subject, newKey(), request);
                }));
            }
            go.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> result : results) {
                statuses.add(HttpStatus.valueOf(result.get(60, TimeUnit.SECONDS).getStatusCode().value()));
            }
            assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED).hasSize(1);
            assertThat(statuses).filteredOn(s -> s != HttpStatus.CREATED).allMatch(s -> s == HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(transferCount(subject)).isEqualTo(1);
    }

    @Test
    void staleQuotesAreRejectedAndNothingIsWritten() {
        String subject = customer();
        UUID quoteId = eurToMadQuote(subject);
        UUID beneficiaryId = beneficiary(subject, "MA");

        clock.set(start.plus(QUOTE_TTL));
        assertThat(create(subject, newKey(), body(quoteId, beneficiaryId, "FAMILY_SUPPORT")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(quoteStatus(quoteId)).isEqualTo("ISSUED");
        assertThat(transferCount(subject)).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE customer_id = ? AND operation = 'CREATE_TRANSFER'",
                customerId(subject))).isZero();
    }

    @Test
    void foreignUnknownAndDeletedResourcesAreNotFound() {
        String subject = customer();
        String other = customer();
        UUID ownQuote = eurToMadQuote(subject);
        UUID ownBeneficiary = beneficiary(subject, "MA");

        assertThat(create(subject, newKey(), body(eurToMadQuote(other), ownBeneficiary, "FAMILY_SUPPORT"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(create(subject, newKey(), body(UUID.randomUUID(), ownBeneficiary, "FAMILY_SUPPORT"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(create(subject, newKey(), body(ownQuote, beneficiary(other, "MA"), "FAMILY_SUPPORT"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(create(subject, newKey(), body(ownQuote, UUID.randomUUID(), "FAMILY_SUPPORT"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        jdbc.update("UPDATE beneficiaries SET deleted_at = now() WHERE id = ?", ownBeneficiary);
        assertThat(create(subject, newKey(), body(ownQuote, ownBeneficiary, "FAMILY_SUPPORT")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Every rejection rolled back: the quote is still usable.
        assertThat(quoteStatus(ownQuote)).isEqualTo("ISSUED");
        assertThat(transferCount(subject)).isZero();
    }

    @Test
    void beneficiaryOutsideTheQuotePayoutCorridorIsUnprocessable() {
        String subject = customer();
        UUID quoteId = eurToMadQuote(subject);
        assertThat(create(subject, newKey(), body(quoteId, beneficiary(subject, "ES"), "FAMILY_SUPPORT"))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(quoteStatus(quoteId)).isEqualTo("ISSUED");
        assertThat(transferCount(subject)).isZero();
    }

    @Test
    void onlyAuthenticatedEligibleCustomersCreateTransfers() {
        assertThat(create(null, newKey(), body(UUID.randomUUID(), UUID.randomUUID(), "FAMILY_SUPPORT"))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(create(TestOidcIssuer.UNPROVISIONED_SUB, newKey(),
                body(UUID.randomUUID(), UUID.randomUUID(), "FAMILY_SUPPORT")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        for (String[] state : new String[][] {
            {"REJECTED", "ACTIVE"}, {"EXPIRED", "ACTIVE"}, {"PENDING", "ACTIVE"},
            {"APPROVED", "SUSPENDED"}, {"APPROVED", "CLOSED"}}) {
            String subject = customer();
            UUID quoteId = eurToMadQuote(subject);
            UUID beneficiaryId = beneficiary(subject, "MA");
            // Eligibility revoked after the quote was issued.
            jdbc.update("UPDATE customers SET kyc_status = ?, status = ? WHERE oidc_subject = ?",
                    state[0], state[1], subject);
            assertThat(create(subject, newKey(), body(quoteId, beneficiaryId, "FAMILY_SUPPORT")).getStatusCode())
                    .as(String.join("/", state)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(quoteStatus(quoteId)).isEqualTo("ISSUED");
            assertThat(transferCount(subject)).isZero();
        }
    }

    @Test
    void malformedRequestsAreBadRequestAndWriteNothing() {
        String subject = customer();
        UUID quoteId = eurToMadQuote(subject);
        UUID beneficiaryId = beneficiary(subject, "MA");
        Map<String, Object> numericPurpose = body(quoteId, beneficiaryId, 1);
        List<Object> bodies = List.of(
                body(null, beneficiaryId, "FAMILY_SUPPORT"),
                body(quoteId, null, "FAMILY_SUPPORT"),
                body(quoteId, beneficiaryId, null),
                body(quoteId, beneficiaryId, "GIFT"),
                body("1-1-1-1-1", beneficiaryId, "FAMILY_SUPPORT"),
                body("not-a-uuid", beneficiaryId, "FAMILY_SUPPORT"),
                numericPurpose,
                List.of("not", "an", "object"),
                "\"just a string\"");
        for (Object body : bodies) {
            assertThat(create(subject, newKey(), body).getStatusCode()).as(String.valueOf(body))
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> valid = body(quoteId, beneficiaryId, "FAMILY_SUPPORT");
        assertThat(create(subject, null, valid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(subject, "short", valid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(subject, "has space key", valid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String json = "{\"quoteId\":\"" + quoteId + "\",\"beneficiaryId\":\"" + beneficiaryId
                + "\",\"purpose\":\"FAMILY_SUPPORT\"}";
        ResponseEntity<JsonNode> text = post(PATH, subject, newKey(), json, MediaType.TEXT_PLAIN);
        assertThat(text.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(quoteStatus(quoteId)).isEqualTo("ISSUED");
        assertThat(transferCount(subject)).isZero();
    }

    @Test
    void lockTimeoutReturns503WritesNothingAndSameKeyRecovers() throws Exception {
        String subject = customer();
        UUID owner = customerId(subject);
        Map<String, Object> request = body(eurToMadQuote(subject), beneficiary(subject, "MA"), "FAMILY_SUPPORT");
        String key = newKey();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.queryForList("SELECT id FROM customers WHERE id = ? FOR UPDATE", owner);
                locked.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            ResponseEntity<JsonNode> blocked = create(subject, key, request);
            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(transferCount(subject)).isZero();
            assertThat(count("SELECT count(*) FROM idempotency_keys WHERE customer_id = ? AND"
                    + " operation = 'CREATE_TRANSFER'", owner)).isZero();

            release.countDown();
            holding.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
        ResponseEntity<JsonNode> retried = create(subject, key, request);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create(subject, key, request).getBody().path("id")).isEqualTo(retried.getBody().path("id"));
        assertThat(transferCount(subject)).isEqualTo(1);
    }

    @Test
    void replayAfterEligibilityIsRevokedStillFollowsIdempotency() {
        for (String[] state : new String[][] {{"EXPIRED", "ACTIVE"}, {"APPROVED", "SUSPENDED"}}) {
            String subject = customer();
            UUID quoteId = eurToMadQuote(subject);
            UUID beneficiaryId = beneficiary(subject, "MA");
            String key = newKey();
            Map<String, Object> request = body(quoteId, beneficiaryId, "FAMILY_SUPPORT");
            ResponseEntity<JsonNode> first = create(subject, key, request);
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID unusedQuote = eurToMadQuote(subject);

            // The response was lost, then eligibility was revoked before the retry.
            jdbc.update("UPDATE customers SET kyc_status = ?, status = ? WHERE oidc_subject = ?",
                    state[0], state[1], subject);
            ResponseEntity<JsonNode> replay = create(subject, key, request);
            assertThat(replay.getStatusCode()).as(String.join("/", state)).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.getBody()).isEqualTo(first.getBody());
            assertThat(create(subject, key, body(quoteId, beneficiaryId, "EDUCATION")).getStatusCode())
                    .as(String.join("/", state)).isEqualTo(HttpStatus.CONFLICT);
            // A new key is a new transfer, which the ineligible customer may not create.
            assertThat(create(subject, newKey(), body(unusedQuote, beneficiaryId, "FAMILY_SUPPORT"))
                    .getStatusCode()).as(String.join("/", state)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(quoteStatus(unusedQuote)).isEqualTo("ISSUED");
            assertThat(transferCount(subject)).isEqualTo(1);
        }
    }

    @Test
    void beneficiaryDeletedConcurrentlyIsNotFoundAndLeavesTheQuoteUnconsumed() throws Exception {
        String subject = customer();
        UUID quoteId = eurToMadQuote(subject);
        UUID beneficiaryId = beneficiary(subject, "MA");
        Map<String, Object> request = body(quoteId, beneficiaryId, "FAMILY_SUPPORT");

        ResponseEntity<JsonNode> response = whileBeneficiaryDeleteIsInFlight(beneficiaryId, true,
                () -> create(subject, newKey(), request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(quoteStatus(quoteId)).isEqualTo("ISSUED");
        assertThat(transferCount(subject)).isZero();
    }

    @Test
    void beneficiaryLockHeldPastTheTimeoutIs503AndLeavesTheQuoteUnconsumed() throws Exception {
        String subject = customer();
        UUID quoteId = eurToMadQuote(subject);
        UUID beneficiaryId = beneficiary(subject, "MA");
        Map<String, Object> request = body(quoteId, beneficiaryId, "FAMILY_SUPPORT");
        String key = newKey();

        // The delete is held until the create has given up, then rolled back.
        ResponseEntity<JsonNode> blocked = whileBeneficiaryDeleteIsInFlight(beneficiaryId, false,
                () -> create(subject, key, request));

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(quoteStatus(quoteId)).isEqualTo("ISSUED");
        assertThat(transferCount(subject)).isZero();
        assertThat(create(subject, key, request).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * Soft-deletes the beneficiary in an open transaction, runs {@code action} in another thread
     * and, once that is waiting for the beneficiary row lock, either commits the delete at once
     * ({@code commitWhileWaiting}) or holds it until {@code action} returns and then rolls it back.
     */
    private <T> T whileBeneficiaryDeleteIsInFlight(UUID beneficiaryId, boolean commitWhileWaiting,
            java.util.concurrent.Callable<T> action) throws Exception {
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> deleting = pool.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.update("UPDATE beneficiaries SET deleted_at = now() WHERE id = ?", beneficiaryId);
                deleted.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (!commitWhileWaiting) {
                    tx.setRollbackOnly();
                }
            }));
            assertThat(deleted.await(30, TimeUnit.SECONDS)).isTrue();
            Future<T> result = pool.submit(action);
            if (commitWhileWaiting) {
                // Release only once the create is blocked on the beneficiary row (before its timeout).
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                        + " AND query ILIKE '%FROM beneficiaries%FOR SHARE%'") == 0) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(5);
                }
                release.countDown();
                deleting.get(30, TimeUnit.SECONDS);
                return result.get(30, TimeUnit.SECONDS);
            }
            T value = result.get(30, TimeUnit.SECONDS);
            release.countDown();
            deleting.get(30, TimeUnit.SECONDS);
            return value;
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ---- reads ----------------------------------------------------------------------------------

    @Test
    void readsAreOwnerScoped() {
        String subject = customer();
        UUID older = createdTransfer(subject);
        clock.set(start.plusSeconds(1));
        UUID newer = createdTransfer(subject);
        String other = customer();
        UUID foreign = createdTransfer(other);

        ResponseEntity<JsonNode> one = get(subject, PATH + "/" + older);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(one.getBody().path("id").stringValue()).isEqualTo(older.toString());
        assertThat(one.getBody().path("status").stringValue()).isEqualTo("AWAITING_FUNDING");
        assertThat(one.getBody().has("customerId")).isFalse();

        assertThat(get(subject, PATH + "/" + foreign).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(subject, PATH + "/" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(subject, PATH + "/not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(null, PATH).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<JsonNode> list = get(subject, PATH);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        list.getBody().forEach(t -> ids.add(t.path("id").stringValue()));
        assertThat(ids).containsExactly(newer.toString(), older.toString());

        // History stays readable after eligibility is revoked and after the beneficiary is deleted.
        jdbc.update("UPDATE customers SET status = 'SUSPENDED' WHERE oidc_subject = ?", subject);
        jdbc.update("UPDATE beneficiaries SET deleted_at = now() WHERE id = ?::uuid",
                one.getBody().path("beneficiaryId").stringValue());
        assertThat(get(subject, PATH).getBody()).hasSize(2);
        assertThat(get(subject, PATH + "/" + older).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- state machine --------------------------------------------------------------------------

    @Test
    void serviceTransitionsFollowTheStateMachineAndRecordEvents() {
        String subject = customer();
        UUID id = createdTransfer(subject);

        assertThat(transition(id, TransferStatus.AWAITING_FUNDING, TransferStatus.COMPLETED))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(transition(id, TransferStatus.PROCESSING, TransferStatus.COMPLETED))
                .isEqualTo(HttpStatus.CONFLICT); // stale expectation
        assertThat(transition(id, TransferStatus.AWAITING_FUNDING, TransferStatus.FUNDING_CONFIRMED))
                .isEqualTo(HttpStatus.OK);
        // Replaying the same decision does not apply twice.
        assertThat(transition(id, TransferStatus.AWAITING_FUNDING, TransferStatus.FUNDING_CONFIRMED))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(transition(id, TransferStatus.FUNDING_CONFIRMED, TransferStatus.PROCESSING)).isEqualTo(HttpStatus.OK);
        assertThat(transition(id, TransferStatus.PROCESSING, TransferStatus.COMPLETED)).isEqualTo(HttpStatus.OK);
        for (TransferStatus target : TransferStatus.values()) {
            assertThat(transition(id, TransferStatus.COMPLETED, target)).as(target.name())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
        assertThat(transition(UUID.randomUUID(), TransferStatus.CREATED, TransferStatus.AWAITING_FUNDING))
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(jdbc.queryForMap("SELECT status, version FROM transfers WHERE id = ?", id))
                .containsEntry("status", "COMPLETED").containsEntry("version", 4L);
        assertThat(jdbc.queryForList("SELECT next_status FROM transaction_events WHERE resource_id = ?"
                + " AND actor_type = 'PROVIDER' ORDER BY recorded_at", String.class, id))
                .containsExactly("FUNDING_CONFIRMED", "PROCESSING", "COMPLETED");
        // Must run inside the caller's transaction.
        assertThatThrownBy(() -> transfers.transition(id, new TransferService.Observed(TransferStatus.COMPLETED, 4),
                TransferStatus.REFUNDED, TransferService.ActorType.SYSTEM, "x", "X", UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void decisionMadeBeforeTheTransferReturnedToTheSameStatusIsStale() {
        UUID id = createdTransfer(customer());
        assertThat(transition(id, TransferStatus.AWAITING_FUNDING, TransferStatus.FUNDING_CONFIRMED))
                .isEqualTo(HttpStatus.OK);
        assertThat(transition(id, TransferStatus.FUNDING_CONFIRMED, TransferStatus.PROCESSING))
                .isEqualTo(HttpStatus.OK);
        // A decision is made while PROCESSING ...
        TransferService.Observed decidedOn = transfers.observe(id).orElseThrow();
        assertThat(decidedOn.status()).isEqualTo(TransferStatus.PROCESSING);
        // ... then the transfer goes through review and back to PROCESSING.
        assertThat(transition(id, TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED)).isEqualTo(HttpStatus.OK);
        assertThat(transition(id, TransferStatus.REVIEW_REQUIRED, TransferStatus.PROCESSING)).isEqualTo(HttpStatus.OK);

        assertThat(transition(id, decidedOn, TransferStatus.COMPLETED)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForMap("SELECT status, version FROM transfers WHERE id = ?", id))
                .containsEntry("status", "PROCESSING").containsEntry("version", 5L);
        assertThat(transition(id, transfers.observe(id).orElseThrow(), TransferStatus.COMPLETED))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void databaseTransitionGraphMatchesTheApplication() {
        for (TransferStatus from : TransferStatus.values()) {
            for (TransferStatus to : TransferStatus.values()) {
                assertThat(jdbc.queryForObject("SELECT transfer_transition_allowed(?, ?)", Boolean.class,
                        from.name(), to.name())).as(from + "->" + to).isEqualTo(from.canTransitionTo(to));
            }
        }
    }

    @Test
    void databaseRejectsInvalidTransitionsAndEdits() {
        String subject = customer();
        UUID id = createdTransfer(subject);
        String update = "UPDATE transfers SET status = ?, version = version + 1 WHERE id = ?";

        for (String target : List.of("CREATED", "COMPLETED", "PROCESSING", "REFUNDED", "FAILED")) {
            assertThatThrownBy(() -> jdbc.update(update, target, id)).as(target)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        // Status changes must advance the version by exactly one.
        assertThatThrownBy(() -> jdbc.update("UPDATE transfers SET status = 'CANCELLED' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID otherBeneficiary = beneficiary(subject, "MA");
        for (String change : List.of("customer_id = '" + customerId(customer()) + "'",
                "beneficiary_id = '" + otherBeneficiary + "'", "purpose = 'EDUCATION'", "provider = 'LIVE_PSP'",
                "created_at = created_at - interval '1 second'", "quote_id = '" + eurToMadQuote(subject) + "'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE transfers SET " + change + ", version = version + 1"
                    + " WHERE id = ?", id)).as(change).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transfers WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A valid status change without a matching lifecycle event in the same transaction rolls back.
        assertThatThrownBy(() -> jdbc.update(update, "CANCELLED", id)).isInstanceOf(DataIntegrityViolationException.class);
        // An event from another transaction, or for a different transition, does not count.
        insertEvent(id, "AWAITING_FUNDING", "CANCELLED");
        assertThatThrownBy(() -> jdbc.update(update, "CANCELLED", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            insertEvent(id, "CREATED", "CANCELLED");
            jdbc.update(update, "CANCELLED", id);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForMap("SELECT status, version FROM transfers WHERE id = ?", id))
                .containsEntry("status", "AWAITING_FUNDING").containsEntry("version", 1L);

        transactions.executeWithoutResult(tx -> {
            assertThat(jdbc.update(update, "CANCELLED", id)).isEqualTo(1);
            insertEvent(id, "AWAITING_FUNDING", "CANCELLED");
        });
        // CANCELLED is terminal: no status change out of it is accepted.
        for (TransferStatus target : TransferStatus.values()) {
            if (target == TransferStatus.CANCELLED) {
                continue;
            }
            assertThatThrownBy(() -> jdbc.update(update, target.name(), id)).as(target.name())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, id))
                .isEqualTo("CANCELLED");
    }

    @Test
    void databaseRejectsTransfersThatBypassQuoteConsumptionOrOwnership() {
        String subject = customer();
        UUID owner = customerId(subject);
        UUID beneficiaryId = beneficiary(subject, "MA");
        String insert = "INSERT INTO transfers (customer_id, quote_id, beneficiary_id, purpose, status)"
                + " VALUES (?, ?, ?, 'FAMILY_SUPPORT', ?)";

        UUID issued = eurToMadQuote(subject);
        assertThatThrownBy(() -> jdbc.update(insert, owner, issued, beneficiaryId, "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID consumed = eurToMadQuote(subject);
        jdbc.update("UPDATE fx_quotes SET status = 'CONSUMED' WHERE id = ?", consumed);
        assertThatThrownBy(() -> jdbc.update(insert, owner, consumed, beneficiaryId, "AWAITING_FUNDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, owner, consumed, beneficiary(customer(), "MA"), "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO transfers (customer_id, quote_id, beneficiary_id, purpose,"
                + " provider) VALUES (?, ?, ?, 'FAMILY_SUPPORT', 'LIVE_PSP')", owner, consumed, beneficiaryId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A valid insert still needs its CREATED lifecycle event in the same transaction.
        assertThatThrownBy(() -> jdbc.update(insert, owner, consumed, beneficiaryId, "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO transfers (id, customer_id, quote_id, beneficiary_id, purpose) VALUES"
                    + " (?, ?, ?, ?, 'FAMILY_SUPPORT')", id, owner, consumed, beneficiaryId);
            insertEvent(id, null, "CREATED");
        });
        // One transfer per quote.
        assertThatThrownBy(() -> jdbc.update(insert, owner, consumed, beneficiaryId, "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertEvent(UUID transferId, String previous, String next) {
        jdbc.update("INSERT INTO transaction_events (resource_kind, resource_id, previous_status, next_status,"
                + " actor_type, actor_ref, correlation_id) VALUES ('TRANSFER', ?, ?, ?, 'OPERATOR', 'test', ?)",
                transferId, previous, next, UUID.randomUUID());
    }

    @Test
    void lifecycleEventsAreAppendOnly() {
        UUID id = createdTransfer(customer());
        assertThatThrownBy(() -> jdbc.update("UPDATE transaction_events SET reason_code = 'EDITED'"
                + " WHERE resource_id = ?", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transaction_events WHERE resource_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("SELECT count(*) FROM transaction_events WHERE resource_id = ?", id)).isEqualTo(2);
    }
}
