package com.eurmad.api.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eurmad.api.RestTestSupport;
import com.eurmad.api.error.ApiProblemException;
import com.eurmad.api.security.TestOidcIssuer;
import java.time.Clock;
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
 * FX-007 quote issuance, idempotency, eligibility, stale-quote rejection and DB immutability
 * against PostgreSQL. The server clock is a controllable test clock. All data is synthetic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Testcontainers(disabledWithoutDocker = true)
@Import(QuoteIntegrationTest.ClockConfig.class)
class QuoteIntegrationTest {

    private static final String PATH = "/v1/quotes";
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

    /** Server clock under test control; reset to the real time before each test. */
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
    @Autowired private QuoteService quotes;
    @Autowired private TransactionTemplate transactions;

    private Instant start;

    @BeforeEach
    void resetClock() {
        start = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(start);
    }

    private String customer(String kycStatus, String status) {
        String subject = "test:fx-007:synthetic-" + UUID.randomUUID();
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

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static Map<String, Object> body(String source, String target, Object amount, String purpose) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceCurrency", source);
        body.put("targetCurrency", target);
        body.put("sourceAmountMinor", amount);
        body.put("purpose", purpose);
        return body;
    }

    private static Map<String, Object> eurToMad(String amount) {
        return body("EUR", "MAD", amount, "FAMILY_SUPPORT");
    }

    private static String newKey() {
        return "idem-" + UUID.randomUUID();
    }

    private ResponseEntity<JsonNode> create(String subject, String key, Object body) {
        return create(subject, key, body, MediaType.APPLICATION_JSON);
    }

    private ResponseEntity<JsonNode> create(String subject, String key, Object body, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.setBearerAuth(ISSUER.token(subject));
        }
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        headers.setContentType(contentType);
        return RestTestSupport.exchange(rest, PATH, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private UUID createdId(String subject) {
        ResponseEntity<JsonNode> response = create(subject, newKey(), eurToMad("10000"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().path("id").stringValue());
    }

    private HttpStatus requireFresh(UUID customerId, UUID quoteId) {
        try {
            transactions.executeWithoutResult(tx -> quotes.requireFreshForUpdate(customerId, quoteId));
            return HttpStatus.OK;
        } catch (ApiProblemException e) {
            return e.status();
        }
    }

    @Test
    void issuesImmutableSimulatedEurToMadQuote() {
        String subject = customer();

        ResponseEntity<JsonNode> response = create(subject, newKey(), eurToMad("10000"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        UUID correlationId = UUID.fromString(response.getHeaders().getFirst("X-Correlation-Id"));
        JsonNode quote = response.getBody();
        assertThat(quote.propertyNames()).containsExactlyInAnyOrder("id", "sourceCurrency", "targetCurrency",
                "sourceAmountMinor", "targetAmountMinor", "feeSourceMinor", "totalDebitMinor", "rate", "rateSource",
                "expiresAt", "mode", "status");
        assertThat(quote.path("sourceCurrency").stringValue()).isEqualTo("EUR");
        assertThat(quote.path("targetCurrency").stringValue()).isEqualTo("MAD");
        // Money and rate are JSON strings, never numbers.
        assertThat(quote.path("sourceAmountMinor").isString()).isTrue();
        assertThat(quote.path("sourceAmountMinor").stringValue()).isEqualTo("10000");
        assertThat(quote.path("targetAmountMinor").stringValue()).isEqualTo("107000");
        assertThat(quote.path("feeSourceMinor").stringValue()).isEqualTo("249");
        assertThat(quote.path("totalDebitMinor").stringValue()).isEqualTo("10249");
        assertThat(quote.path("rate").stringValue()).isEqualTo("10.700000000000");
        assertThat(quote.path("rateSource").stringValue()).isEqualTo("SIMULATED_ILLUSTRATIVE");
        assertThat(quote.path("mode").stringValue()).isEqualTo("SIMULATION");
        assertThat(quote.path("status").stringValue()).isEqualTo("ISSUED");
        assertThat(Instant.parse(quote.path("expiresAt").stringValue())).isEqualTo(start.plus(QuotePolicy.TTL));

        UUID id = UUID.fromString(quote.path("id").stringValue());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM fx_quotes WHERE id = ?", id);
        assertThat(row.get("customer_id")).isEqualTo(customerId(subject));
        assertThat(row).containsEntry("source_amount_minor", 10000L).containsEntry("target_amount_minor", 107000L)
                .containsEntry("fee_source_minor", 249L).containsEntry("total_debit_minor", 10249L)
                .containsEntry("mode", "SIMULATION").containsEntry("status", "ISSUED")
                .containsEntry("quote_type", "TRANSFER")
                // Fee breakdown: fixed EUR 1.99; the variable part is fee_source_minor - fee_fixed_minor.
                .containsEntry("fee_fixed_minor", 199L);
        assertThat(row.get("rate").toString()).isEqualTo("10.700000000000");

        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_ref, action, resource_type, safe_metadata::text AS m"
                + " FROM audit_events WHERE resource_id = ?", id.toString());
        assertThat(audit).containsEntry("actor_ref", customerId(subject).toString())
                .containsEntry("action", "QUOTE_ISSUED").containsEntry("resource_type", "FX_QUOTE");
        assertThat((String) audit.get("m")).contains(correlationId.toString(), "SIMULATION");
        // Only enum/label metadata; no amounts, rate or customer data.
        assertThat(jdbc.queryForList("SELECT jsonb_object_keys(safe_metadata) FROM audit_events WHERE resource_id = ?",
                String.class, id.toString()))
                .containsExactlyInAnyOrder("sourceCurrency", "targetCurrency", "rateSource", "mode", "correlationId");
    }

    @Test
    void issuesSimulatedMadToEurQuoteWithInverseRate() {
        ResponseEntity<JsonNode> response = create(customer(), newKey(),
                body("MAD", "EUR", "100000", "PERSONAL_TRANSFER"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode quote = response.getBody();
        assertThat(quote.path("rate").stringValue()).isEqualTo("0.091743119266");
        assertThat(quote.path("targetAmountMinor").stringValue()).isEqualTo("9174");
        assertThat(quote.path("feeSourceMinor").stringValue()).isEqualTo("2500");
        assertThat(quote.path("totalDebitMinor").stringValue()).isEqualTo("102500");
        assertThat(quote.path("mode").stringValue()).isEqualTo("SIMULATION");
    }

    @Test
    void identicalReplayReturnsTheSameQuoteAndDifferentPayloadConflicts() {
        String subject = customer();
        String key = newKey();
        ResponseEntity<JsonNode> first = create(subject, key, eurToMad("10000"));
        // Leading zeros normalize to the same request.
        ResponseEntity<JsonNode> replay = create(subject, key, eurToMad("010000"));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(create(subject, key, eurToMad("20000")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(create(subject, key, body("EUR", "MAD", "10000", "EDUCATION")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("SELECT count(*) FROM fx_quotes WHERE customer_id = ?", customerId(subject))).isEqualTo(1);

        // Keys are scoped per customer: another customer's identical key issues its own quote.
        String other = customer();
        ResponseEntity<JsonNode> foreign = create(other, key, eurToMad("10000"));
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(foreign.getBody().path("id")).isNotEqualTo(first.getBody().path("id"));
    }

    @Test
    void replayAfterExpiryReportsTheOriginalQuoteAsExpired() {
        String subject = customer();
        String key = newKey();
        ResponseEntity<JsonNode> first = create(subject, key, eurToMad("10000"));

        clock.set(start.plus(QuotePolicy.TTL));
        ResponseEntity<JsonNode> replay = create(subject, key, eurToMad("10000"));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("id")).isEqualTo(first.getBody().path("id"));
        assertThat(replay.getBody().path("expiresAt")).isEqualTo(first.getBody().path("expiresAt"));
        assertThat(replay.getBody().path("status").stringValue()).isEqualTo("EXPIRED");
    }

    @Test
    void concurrentIdenticalCreatesProduceOneQuote() throws Exception {
        String subject = customer();
        String key = newKey();
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return create(subject, key, eurToMad("10000"));
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
        assertThat(count("SELECT count(*) FROM fx_quotes WHERE customer_id = ?", customerId(subject))).isEqualTo(1);
    }

    @Test
    void malformedRequestsAreBadRequestAndWriteNothing() {
        String subject = customer();
        List<Object> bodies = List.of(
                body("EUR", "MAD", 10000, "FAMILY_SUPPORT"),        // JSON number, not a string
                body("EUR", "MAD", "10000.00", "FAMILY_SUPPORT"),
                body("EUR", "MAD", "0", "FAMILY_SUPPORT"),
                body("EUR", "MAD", "-100", "FAMILY_SUPPORT"),
                body("EUR", "EUR", "10000", "FAMILY_SUPPORT"),
                body("eur", "MAD", "10000", "FAMILY_SUPPORT"),
                body("EUR", "MAD", "10000", "GIFT"),
                body("EUR", "MAD", "10000", null),
                List.of("not", "an", "object"),
                "\"just a string\"");
        for (Object body : bodies) {
            assertThat(create(subject, newKey(), body).getStatusCode()).as(String.valueOf(body))
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(create(subject, null, eurToMad("10000")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(subject, "short", eurToMad("10000")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("SELECT count(*) FROM fx_quotes WHERE customer_id = ?", customerId(subject))).isZero();
    }

    @Test
    void nonJsonContentTypesAreBadRequestAndWriteNothing() {
        String subject = customer();
        String json = "{\"sourceCurrency\":\"EUR\",\"targetCurrency\":\"MAD\",\"sourceAmountMinor\":\"10000\","
                + "\"purpose\":\"FAMILY_SUPPORT\"}";
        for (MediaType type : List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_XML,
                MediaType.APPLICATION_FORM_URLENCODED)) {
            ResponseEntity<JsonNode> response = create(subject, newKey(), json, type);
            assertThat(response.getStatusCode()).as(type.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType()).as(type.toString())
                    .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().path("code").stringValue()).isEqualTo("BAD_REQUEST");
        }
        assertThat(count("SELECT count(*) FROM fx_quotes WHERE customer_id = ?", customerId(subject))).isZero();
    }

    @Test
    void zeroPaddedAmountsAreNormalizedAndAllZeroAmountsAreBadRequest() {
        String subject = customer();
        ResponseEntity<JsonNode> padded = create(subject, newKey(), eurToMad("0".repeat(60) + "10000"));
        assertThat(padded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(padded.getBody().path("sourceAmountMinor").stringValue()).isEqualTo("10000");
        assertThat(create(subject, newKey(), eurToMad("0".repeat(60))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("SELECT count(*) FROM fx_quotes WHERE customer_id = ?", customerId(subject))).isEqualTo(1);
    }

    @Test
    void lockTimeoutReturns503WritesNothingAndSameKeyRecovers() throws Exception {
        String subject = customer();
        UUID owner = customerId(subject);
        String key = newKey();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            // A concurrent transaction holds the customer row lock for longer than the lock timeout.
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

            ResponseEntity<JsonNode> blocked = create(subject, key, eurToMad("10000"));

            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(blocked.getBody().path("code").stringValue()).isEqualTo("SERVICE_UNAVAILABLE");
            // Rolled back: no quote, idempotency record or audit event.
            assertThat(count("SELECT count(*) FROM fx_quotes WHERE customer_id = ?", owner)).isZero();
            assertThat(count("SELECT count(*) FROM idempotency_keys WHERE customer_id = ?", owner)).isZero();
            assertThat(count("SELECT count(*) FROM audit_events WHERE actor_ref = ? AND resource_type = 'FX_QUOTE'",
                    owner.toString())).isZero();

            release.countDown();
            holding.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }

        ResponseEntity<JsonNode> retried = create(subject, key, eurToMad("10000"));
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count("SELECT count(*) FROM fx_quotes WHERE customer_id = ?", owner)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE customer_id = ?", owner)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE actor_ref = ? AND action = 'QUOTE_ISSUED'",
                owner.toString())).isEqualTo(1);
        // A replay after recovery returns the same quote.
        assertThat(create(subject, key, eurToMad("10000")).getBody().path("id"))
                .isEqualTo(retried.getBody().path("id"));
    }

    @Test
    void amountsOutsideSimulatedLimitsAreUnprocessable() {
        String subject = customer();
        for (String amount : List.of("99", "1000001", "99999999999999999999999")) {
            assertThat(create(subject, newKey(), eurToMad(amount)).getStatusCode()).as(amount)
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(create(subject, newKey(), body("MAD", "EUR", "999", "FAMILY_SUPPORT")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(count("SELECT count(*) FROM fx_quotes WHERE customer_id = ?", customerId(subject))).isZero();
    }

    @Test
    void onlyAuthenticatedEligibleCustomersReceiveQuotes() {
        assertThat(create(null, newKey(), eurToMad("10000")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(create(TestOidcIssuer.UNPROVISIONED_SUB, newKey(), eurToMad("10000")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        for (String[] state : new String[][] {
            {"NOT_STARTED", "ACTIVE"}, {"PENDING", "ACTIVE"}, {"REJECTED", "ACTIVE"}, {"EXPIRED", "ACTIVE"},
            {"APPROVED", "SUSPENDED"}, {"APPROVED", "CLOSED"}}) {
            String subject = customer(state[0], state[1]);
            assertThat(create(subject, newKey(), eurToMad("10000")).getStatusCode()).as(String.join("/", state))
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(count("SELECT count(*) FROM fx_quotes WHERE customer_id = ?", customerId(subject))).isZero();
        }
    }

    @Test
    void staleQuotesAreRejectedFromTheExpiryInstant() {
        String subject = customer();
        UUID owner = customerId(subject);
        UUID id = createdId(subject);

        clock.set(start.plus(QuotePolicy.TTL).minusNanos(1000));
        assertThat(requireFresh(owner, id)).isEqualTo(HttpStatus.OK);
        clock.set(start.plus(QuotePolicy.TTL));
        assertThat(requireFresh(owner, id)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        clock.set(start.plus(QuotePolicy.TTL).plusSeconds(3600));
        assertThat(requireFresh(owner, id)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // The rejection changes nothing persisted.
        assertThat(jdbc.queryForObject("SELECT status FROM fx_quotes WHERE id = ?", String.class, id))
                .isEqualTo("ISSUED");
    }

    @Test
    void databaseRejectsConsumptionOnceTheDeadlinePassesAfterTheFreshnessCheck() {
        String subject = customer();
        UUID owner = customerId(subject);
        // Issued so that, by the real (database) clock, the quote expires about 3 s from now.
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(QuotePolicy.TTL).plusSeconds(3);
        clock.set(issuedAt);
        UUID id = createdId(subject);

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            // The application check passes (server clock still before expiry) and the row is locked.
            quotes.requireFreshForUpdate(owner, id);
            // The transaction began before the deadline, so now() alone would still accept it.
            assertThat(jdbc.queryForObject("SELECT now() < expires_at FROM fx_quotes WHERE id = ?",
                    Boolean.class, id)).isTrue();
            awaitDatabaseClockPast(id);
            jdbc.update("UPDATE fx_quotes SET status = 'CONSUMED' WHERE id = ?", id);
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM fx_quotes WHERE id = ?", String.class, id))
                .isEqualTo("ISSUED");
        // A fresh quote can still be consumed.
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        UUID fresh = createdId(subject);
        assertThat(jdbc.update("UPDATE fx_quotes SET status = 'CONSUMED' WHERE id = ?", fresh)).isEqualTo(1);
    }

    private void awaitDatabaseClockPast(UUID quoteId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT clock_timestamp() >= expires_at FROM fx_quotes WHERE id = ?", Boolean.class, quoteId))) {
            assertThat(System.nanoTime() - deadline).as("database clock passes expiry").isNegative();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void freshnessCheckEnforcesOwnershipAndStatus() {
        String subject = customer();
        UUID owner = customerId(subject);
        UUID id = createdId(subject);

        assertThat(requireFresh(customerId(customer()), id)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(requireFresh(owner, UUID.randomUUID())).isEqualTo(HttpStatus.NOT_FOUND);

        UUID consumed = createdId(subject);
        jdbc.update("UPDATE fx_quotes SET status = 'CONSUMED' WHERE id = ?", consumed);
        assertThat(requireFresh(owner, consumed)).isEqualTo(HttpStatus.CONFLICT);

        UUID retired = createdId(subject);
        jdbc.update("UPDATE fx_quotes SET status = 'EXPIRED' WHERE id = ?", retired);
        assertThat(requireFresh(owner, retired)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // Must be called inside the caller's transaction so the row lock is held until consumption.
        assertThatThrownBy(() -> quotes.requireFreshForUpdate(owner, id))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void databaseKeepsQuotesImmutable() {
        String subject = customer();
        UUID id = createdId(subject);

        UUID otherCustomer = customerId(customer());
        for (String change : List.of("rate = rate + 0.000000000001", "target_amount_minor = target_amount_minor + 1",
                "fee_source_minor = 0, total_debit_minor = source_amount_minor",
                "expires_at = expires_at + interval '1 minute'", "customer_id = '" + otherCustomer + "'",
                "created_at = created_at - interval '1 second'", "fee_fixed_minor = 0")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE fx_quotes SET " + change + " WHERE id = ?", id))
                    .as(change).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM fx_quotes WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE fx_quotes SET status = 'CONSUMED' WHERE id = ?", id);
        for (String status : List.of("ISSUED", "EXPIRED")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE fx_quotes SET status = ? WHERE id = ?", status, id))
                    .as(status).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.queryForMap("SELECT rate::text AS rate, target_amount_minor, status FROM fx_quotes"
                + " WHERE id = ?", id)).containsEntry("rate", "10.700000000000")
                .containsEntry("target_amount_minor", 107000L).containsEntry("status", "CONSUMED");
    }

    @Test
    void databaseRejectsNonSimulatedRateSourcesUnboundedExpiryAndMissingFeeBreakdown() {
        UUID owner = customerId(customer());
        String insert = "INSERT INTO fx_quotes (customer_id, source_currency, target_currency, source_amount_minor,"
                + " fee_source_minor, fee_fixed_minor, total_debit_minor, target_amount_minor, rate, rate_source,"
                + " created_at, expires_at) VALUES (?, 'EUR', 'MAD', 100, 199, ?, 299, 1070, 10.7, ?, now(),"
                + " now() + ?::interval)";
        assertThatThrownBy(() -> jdbc.update(insert, owner, 199, "LIVE_PARTNER", "10 minutes"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, owner, 199, "SIMULATED_ILLUSTRATIVE", "31 minutes"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, owner, 199, "SIMULATED_ILLUSTRATIVE", "0 seconds"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Fee breakdown is mandatory for new rows and the fixed part cannot exceed the total fee.
        assertThatThrownBy(() -> jdbc.update(insert, owner, null, "SIMULATED_ILLUSTRATIVE", "10 minutes"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, owner, 200, "SIMULATED_ILLUSTRATIVE", "10 minutes"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, owner, -1, "SIMULATED_ILLUSTRATIVE", "10 minutes"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fx_quotes (customer_id, source_currency, target_currency,"
                + " source_amount_minor, fee_source_minor, fee_fixed_minor, total_debit_minor, target_amount_minor,"
                + " rate, rate_source, mode, created_at, expires_at) VALUES (?, 'MAD', 'EUR', 1000, 0, 0, 1000, 92,"
                + " 0.0917, 'SIMULATED_ILLUSTRATIVE', 'LIVE', now(), now() + interval '10 minutes')", owner))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, owner, 199, "SIMULATED_ILLUSTRATIVE", "10 minutes")).isEqualTo(1);
    }
}
