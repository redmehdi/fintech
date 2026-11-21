package com.eurmad.api.beneficiary;

import static org.assertj.core.api.Assertions.assertThat;

import com.eurmad.api.RestTestSupport;
import com.eurmad.api.security.TestOidcIssuer;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/** FX-006 beneficiary create/list/get/delete, ownership, idempotency and audit against PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class BeneficiaryIntegrationTest {

    private static final String PATH = "/v1/beneficiaries";
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

    @Autowired private RestTestClient rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    /** Synthetic customer with a fresh subject; returns its subject. */
    private String customer(String status) {
        String subject = "test:fx-006:synthetic-" + UUID.randomUUID();
        jdbc.update("INSERT INTO customers (oidc_issuer, oidc_subject, residency_country, kyc_status, status)"
                + " VALUES (?, ?, 'ES', 'APPROVED', ?)", TestOidcIssuer.ISSUER, subject, status);
        return subject;
    }

    private String customer() {
        return customer("ACTIVE");
    }

    private UUID customerId(String subject) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE oidc_subject = ?", UUID.class, subject);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static Map<String, Object> body(String name, String country, String method, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", name);
        body.put("country", country);
        body.put("payoutMethod", method);
        body.put("destinationTokenRef", token);
        return body;
    }

    /** Synthetic token from the sandbox registry, issued for MA / BANK_ACCOUNT. */
    private static final String MA_BANK_TOKEN = "sim-token:ma-bank-mab1";

    private static Map<String, Object> validBody() {
        return body("Synthetic Recipient", "MA", "BANK_ACCOUNT", MA_BANK_TOKEN);
    }

    /** A valid body with one field replaced by an arbitrary JSON value. */
    private static Map<String, Object> validBodyWith(String field, Object value) {
        Map<String, Object> body = validBody();
        body.put(field, value);
        return body;
    }

    private static String newKey() {
        return "idem-" + UUID.randomUUID();
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String subject, String key, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.setBearerAuth(ISSUER.token(subject));
        }
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return RestTestSupport.exchange(rest, path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> create(String subject, String key, Object body) {
        return call(HttpMethod.POST, PATH, subject, key, body);
    }

    private UUID createdId(String subject) {
        ResponseEntity<JsonNode> response = create(subject, newKey(), validBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().path("id").stringValue());
    }

    @Test
    void createReturnsMaskedDestinationAndNeverTheToken() {
        String subject = customer();
        String token = "sim-token:ma-cash-mac2";

        ResponseEntity<JsonNode> response = create(subject, newKey(),
                body("  Synthetic Recipient  ", "MA", "PARTNER_CASH_PICKUP", token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        JsonNode created = response.getBody();
        assertThat(created.path("displayName").stringValue()).isEqualTo("Synthetic Recipient");
        assertThat(created.path("country").stringValue()).isEqualTo("MA");
        assertThat(created.path("payoutMethod").stringValue()).isEqualTo("PARTNER_CASH_PICKUP");
        assertThat(created.path("maskedDestination").stringValue()).isEqualTo("****mac2");
        assertThat(created.propertyNames()).containsExactlyInAnyOrder(
                "id", "displayName", "country", "payoutMethod", "maskedDestination");
        assertThat(created.toString()).doesNotContain(token);

        UUID id = UUID.fromString(created.path("id").stringValue());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM beneficiaries WHERE id = ?", id);
        assertThat(row.get("customer_id")).isEqualTo(customerId(subject));
        assertThat(row.get("destination_token_ref")).isEqualTo(token);
        assertThat(row.get("deleted_at")).isNull();

        ResponseEntity<JsonNode> list = call(HttpMethod.GET, PATH, subject, null, null);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(list.getBody().toString()).doesNotContain(token);
        ResponseEntity<JsonNode> get = call(HttpMethod.GET, PATH + "/" + id, subject, null, null);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).isEqualTo(created);
    }

    @Test
    void listReturnsOnlyOwnActiveBeneficiariesInCreationOrder() {
        String owner = customer();
        String other = customer();
        UUID first = createdId(owner);
        UUID second = createdId(owner);
        UUID foreign = createdId(other);

        ResponseEntity<JsonNode> list = call(HttpMethod.GET, PATH, owner, null, null);

        List<String> ids = new ArrayList<>();
        list.getBody().forEach(node -> ids.add(node.path("id").stringValue()));
        assertThat(ids).containsExactly(first.toString(), second.toString());
        assertThat(ids).doesNotContain(foreign.toString());
        assertThat(call(HttpMethod.GET, PATH, customer(), null, null).getBody().isEmpty()).isTrue();
    }

    @Test
    void crossAccountReadsAndDeletesAreRejectedWithoutDisclosure() {
        String owner = customer();
        String attacker = customer();
        Map<String, Object> request = body("Synthetic Private Name", "ES", "BANK_ACCOUNT", "sim-token:es-bank-esb2");
        UUID id = UUID.fromString(create(owner, newKey(), request).getBody().path("id").stringValue());
        ResponseEntity<JsonNode> unknown = call(HttpMethod.GET, PATH + "/" + UUID.randomUUID(), attacker, null, null);

        ResponseEntity<JsonNode> read = call(HttpMethod.GET, PATH + "/" + id, attacker, null, null);
        ResponseEntity<JsonNode> delete = call(HttpMethod.DELETE, PATH + "/" + id, attacker, null, null);

        for (ResponseEntity<JsonNode> response : List.of(read, delete)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().path("code").stringValue()).isEqualTo(unknown.getBody().path("code").stringValue());
            assertThat(response.getBody().toString()).doesNotContain("Synthetic Private Name", "esb2", id.toString());
        }
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM beneficiaries WHERE id = ?", Object.class, id)).isNull();
        assertThat(call(HttpMethod.GET, PATH + "/" + id, owner, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'BENEFICIARY_DELETED'",
                id.toString())).isZero();
    }

    @Test
    void deletedBeneficiariesAreUnavailableButRetainedAndAudited() {
        String subject = customer();
        UUID kept = createdId(subject);
        UUID id = createdId(subject);

        ResponseEntity<JsonNode> deleted = call(HttpMethod.DELETE, PATH + "/" + id, subject, null, null);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String correlationId = deleted.getHeaders().getFirst("X-Correlation-Id");
        assertThat(UUID.fromString(correlationId)).isNotNull();
        assertThat(call(HttpMethod.GET, PATH + "/" + id, subject, null, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, PATH + "/" + id, subject, null, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode list = call(HttpMethod.GET, PATH, subject, null, null).getBody();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("id").stringValue()).isEqualTo(kept.toString());

        // Soft delete: the row remains for traceability.
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM beneficiaries WHERE id = ?", Object.class, id))
                .isNotNull();
        String meta = jdbc.queryForObject("SELECT safe_metadata::text FROM audit_events"
                + " WHERE resource_type = 'BENEFICIARY' AND resource_id = ? AND action = 'BENEFICIARY_DELETED'",
                String.class, id.toString());
        assertThat(meta).contains("\"correlationId\": \"" + correlationId + "\"");
    }

    @Test
    void identicalReplayReturnsTheSameBeneficiary() {
        String subject = customer();
        String key = newKey();
        Map<String, Object> request = validBody();

        ResponseEntity<JsonNode> first = create(subject, key, request);
        ResponseEntity<JsonNode> replay = create(subject, key, request);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        UUID owner = customerId(subject);
        assertThat(count("SELECT count(*) FROM beneficiaries WHERE customer_id = ?", owner)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE actor_ref = ? AND action = 'BENEFICIARY_CREATED'",
                owner.toString())).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsConflict() {
        String subject = customer();
        String key = newKey();
        create(subject, key, body("Synthetic One", "MA", "BANK_ACCOUNT", "sim-token:ma-bank-mab3"));

        ResponseEntity<JsonNode> conflict =
                create(subject, key, body("Synthetic Two", "MA", "BANK_ACCOUNT", "sim-token:ma-bank-mab3"));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(count("SELECT count(*) FROM beneficiaries WHERE customer_id = ?", customerId(subject)))
                .isEqualTo(1);
    }

    @Test
    void idempotencyKeysAreScopedPerCustomer() {
        String key = newKey();
        Map<String, Object> request = validBody();
        ResponseEntity<JsonNode> a = create(customer(), key, request);
        ResponseEntity<JsonNode> b = create(customer(), key, request);

        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getBody().path("id")).isNotEqualTo(a.getBody().path("id"));
    }

    @Test
    void replayAfterDeletionDoesNotResurrectTheBeneficiary() {
        String subject = customer();
        String key = newKey();
        Map<String, Object> request = validBody();
        String id = create(subject, key, request).getBody().path("id").stringValue();
        call(HttpMethod.DELETE, PATH + "/" + id, subject, null, null);

        ResponseEntity<JsonNode> replay = create(subject, key, request);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(replay.getBody().toString()).doesNotContain(id);
        assertThat(call(HttpMethod.GET, PATH, subject, null, null).getBody().isEmpty()).isTrue();
    }

    @Test
    void concurrentIdenticalCreatesProduceOneBeneficiary() throws Exception {
        String subject = customer();
        String key = newKey();
        Map<String, Object> request = validBody();
        int attempts = 12;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return create(subject, key, request);
                }));
            }
            start.countDown();
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
        assertThat(count("SELECT count(*) FROM beneficiaries WHERE customer_id = ?", customerId(subject)))
                .isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRejectedWithoutStateChange() {
        String subject = customer();
        List<Object> badBodies = List.of(
                body("Name", "FR", "BANK_ACCOUNT", "sim-token:fx-006:bad1"),
                body("Name", "MA", "WALLET", "sim-token:fx-006:bad2"),
                body("Name", null, "BANK_ACCOUNT", "sim-token:fx-006:bad3"),
                body("Name", "MA", null, "sim-token:fx-006:bad4"),
                body("   ", "MA", "BANK_ACCOUNT", "sim-token:fx-006:bad5"),
                body("x".repeat(121), "MA", "BANK_ACCOUNT", "sim-token:fx-006:bad6"),
                body("Name", "MA", "BANK_ACCOUNT", null),
                Map.of("displayName", List.of("array"), "country", "MA", "payoutMethod", "BANK_ACCOUNT",
                        "destinationTokenRef", "sim-token:fx-006:bad7"),
                // JSON scalars that are not strings must not be coerced into strings.
                validBodyWith("displayName", 12345),
                validBodyWith("displayName", true),
                validBodyWith("displayName", 1.5),
                validBodyWith("country", 212),
                validBodyWith("payoutMethod", false),
                validBodyWith("destinationTokenRef", 42),
                validBodyWith("destinationTokenRef", Map.of("token", MA_BANK_TOKEN)),
                List.of(validBody()),
                "not-a-json-object");
        for (Object bad : badBodies) {
            ResponseEntity<JsonNode> response = create(subject, newKey(), bad);
            assertThat(response.getStatusCode()).as(String.valueOf(bad)).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }
        assertThat(create(subject, null, validBody()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(subject, "short", validBody()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(subject, newKey(), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, PATH + "/not-a-uuid", subject, null, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        UUID owner = customerId(subject);
        assertThat(count("SELECT count(*) FROM beneficiaries WHERE customer_id = ?", owner)).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE customer_id = ?", owner)).isZero();
    }

    @Test
    void displayNameLengthCountsCodePointsNotUtf16Units() {
        String subject = customer();
        String maxName = "😀".repeat(120); // 120 code points, 240 UTF-16 code units

        ResponseEntity<JsonNode> created = create(subject, newKey(), validBodyWith("displayName", maxName));
        ResponseEntity<JsonNode> tooLong = create(subject, newKey(), validBodyWith("displayName", maxName + "x"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("displayName").stringValue()).isEqualTo(maxName);
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nonSandboxRawFabricatedOrMismatchedDestinationIsRejectedAndNeverStored() {
        String subject = customer();
        String rawIban = "ES9121000418450200051332"; // synthetic, IBAN-shaped
        String formattedIban = "ES91-2100-0418-4502-0005-1332"; // synthetic, with separators
        List<String> tokens = List.of(rawIban, "sim-token:" + rawIban, "psp-live:tok_synthetic",
                "sim-token:" + formattedIban,    // formatted account number: no 9-digit run
                "sim-token:ES91.2100.0418.4502",
                "sim-token:fabricated-ref-z9y8", // well-formed but never issued
                "sim-token:es-bank-esb9",        // registry-shaped but never issued
                "sim-token:ma-bank-mab1",        // issued for MA, not ES
                "sim-token:es-cash-esc1");       // issued for PARTNER_CASH_PICKUP, not BANK_ACCOUNT
        for (String token : tokens) {
            ResponseEntity<JsonNode> response = create(subject, newKey(), body("Name", "ES", "BANK_ACCOUNT", token));
            assertThat(response.getStatusCode()).as(token).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.getBody().toString()).doesNotContain(rawIban, "tok_synthetic", "0418", "z9y8");
        }
        UUID owner = customerId(subject);
        assertThat(count("SELECT count(*) FROM beneficiaries WHERE customer_id = ?", owner)).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE customer_id = ?", owner)).isZero();
        String stored = jdbc.queryForObject("SELECT coalesce(string_agg(b::text, ''), '') FROM beneficiaries b",
                String.class) + jdbc.queryForObject(
                "SELECT coalesce(string_agg(a::text, ''), '') FROM audit_events a", String.class);
        assertThat(stored).doesNotContain(rawIban, "tok_synthetic", formattedIban, "fabricated-ref");
    }

    @Test
    void malformedBodyIsRejectedWithoutLoggingItsContent(CapturedOutput output) {
        String subject = customer();
        String canary = "SynthLeakCanary4242";
        List<String> malformed = List.of(
                "{\"displayName\": \"Name\", \"destinationTokenRef\": " + canary + "}",
                "{\"displayName\": \"" + canary + "\", \"country\": MA" + canary + "}",
                "{\"destinationTokenRef\": \"sim-token:" + canary + "\"");
        for (String body : malformed) {
            ResponseEntity<JsonNode> response = create(subject, newKey(), body);
            assertThat(response.getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().toString()).doesNotContain(canary);
        }
        assertThat(output.getAll()).doesNotContain(canary);
    }

    @Test
    void heldCustomerLockTimesOutRetryablyWithoutBlockingOtherCustomers() throws Exception {
        String blocked = customer();
        String other = customer();
        String key = newKey();
        UUID blockedId = customerId(blocked);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM customers WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, blockedId);
                lock.executeQuery().close();
            }

            long started = System.nanoTime();
            ResponseEntity<JsonNode> contended = create(blocked, key, validBody());
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(contended.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(contended.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(contended.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(waitedMillis).isLessThan(10_000);
            // Another customer proceeds while the lock is still held.
            assertThat(create(other, newKey(), validBody()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            holder.rollback();
        }
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE customer_id = ?", blockedId)).isZero();

        // Nothing was written, so a retry with the same key succeeds once the lock is released.
        assertThat(create(blocked, key, validBody()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count("SELECT count(*) FROM beneficiaries WHERE customer_id = ?", blockedId)).isEqualTo(1);
    }

    @Test
    void auditRecordsCreationWithoutPersonalOrDestinationData() {
        String subject = customer();
        String token = "sim-token:es-cash-esc1";
        ResponseEntity<JsonNode> response = create(subject, newKey(),
                body("Synthetic Audit Name", "ES", "PARTNER_CASH_PICKUP", token));
        String id = response.getBody().path("id").stringValue();

        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_type, actor_ref, safe_metadata::text AS meta"
                + " FROM audit_events WHERE resource_type = 'BENEFICIARY' AND resource_id = ?"
                + " AND action = 'BENEFICIARY_CREATED'", id);

        assertThat(audit.get("actor_type")).isEqualTo("CUSTOMER");
        assertThat(audit.get("actor_ref")).isEqualTo(customerId(subject).toString());
        String meta = (String) audit.get("meta");
        assertThat(meta).contains("\"country\": \"ES\"", "\"payoutMethod\": \"PARTNER_CASH_PICKUP\"",
                "\"mode\": \"SIMULATION\"",
                "\"correlationId\": \"" + response.getHeaders().getFirst("X-Correlation-Id") + "\"");
        assertThat(meta).doesNotContain("Synthetic Audit Name", token, "esc1", "****");
    }

    @Test
    void suspendedOrClosedCustomersCannotCreateButCanStillRead() {
        for (String status : List.of("SUSPENDED", "CLOSED")) {
            String subject = customer(status);
            assertThat(create(subject, newKey(), validBody()).getStatusCode()).as(status)
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(call(HttpMethod.GET, PATH, subject, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(count("SELECT count(*) FROM beneficiaries WHERE customer_id = ?", customerId(subject)))
                    .isZero();
        }
    }

    @Test
    void unauthenticatedAndUnprovisionedCallersAreRejected() {
        String path = PATH + "/" + UUID.randomUUID();
        assertThat(call(HttpMethod.GET, PATH, null, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(create(null, newKey(), validBody()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, path, null, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.DELETE, path, null, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, PATH, TestOidcIssuer.UNPROVISIONED_SUB, null, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(create(TestOidcIssuer.UNPROVISIONED_SUB, newKey(), validBody()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void schemaRejectsNonSandboxTokensAndInvalidCountryOrMethod() {
        UUID owner = customerId(customer());
        assertThat(insert(owner, "MA", "BANK_ACCOUNT", "sim-token:fx-006:db01", "****db01")).isTrue();
        assertThat(insert(owner, "MA", "BANK_ACCOUNT", "psp-live:tok_x", "****we_x")).isFalse();
        assertThat(insert(owner, "MA", "BANK_ACCOUNT", "sim-token:123456789x", "****789x")).isFalse();
        assertThat(insert(owner, "FR", "BANK_ACCOUNT", "sim-token:fx-006:db02", "****db02")).isFalse();
        assertThat(insert(owner, "MA", "WALLET", "sim-token:fx-006:db03", "****db03")).isFalse();
        assertThat(insert(owner, "MA", "BANK_ACCOUNT", "sim-token:fx-006:db04", "ES9121000418450200051332"))
                .isFalse();
    }

    private boolean insert(UUID owner, String country, String method, String token, String masked) {
        try {
            jdbc.update("INSERT INTO beneficiaries (customer_id, display_name, country, payout_method,"
                    + " destination_token_ref, masked_destination) VALUES (?, 'Synthetic', ?, ?, ?, ?)",
                    owner, country, method, token, masked);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
