package com.eurmad.api.quote;

import com.eurmad.api.error.ApiProblemException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Quote issuance and freshness checks (FX-007). SIMULATION ONLY: the rate source must be the
 * deterministic simulator, otherwise every operation fails closed.
 *
 * <p>Issuance locks the customer row (bounded by a transaction-local {@code lock_timeout}),
 * re-checks eligibility under that lock, and writes the quote, idempotency record and audit event
 * in one transaction. Expiry is computed from the injected server {@link Clock}, never the client.
 */
@Service
public class QuoteService {

    static final String CREATE_OPERATION = "CREATE_QUOTE";
    static final String LOCK_TIMEOUT = "2s";
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String ACTIVE = "ACTIVE";
    private static final String KYC_APPROVED = "APPROVED";

    private final JdbcTemplate jdbc;
    private final QuoteRepository repository;
    private final FxRateProvider rates;
    private final ObjectMapper mapper;
    private final Clock clock;

    QuoteService(JdbcTemplate jdbc, QuoteRepository repository, FxRateProvider rates, ObjectMapper mapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.rates = rates;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Outcome of a create request and the server-generated correlation ID of this request. */
    public record Issued(Quote quote, UUID correlationId) {}

    /**
     * @throws ApiProblemException {@code 503} if the customer row lock is not acquired in time
     */
    @Transactional
    public Issued create(UUID customerId, String idempotencyKey, QuoteRules.ValidRequest request) {
        requireSimulator();
        UUID correlationId = UUID.randomUUID();
        lockEligibleCustomer(customerId);

        String requestHash = requestHash(request);
        Optional<Map<String, Object>> previous = jdbc.queryForList(
                        "SELECT request_sha256, resource_id FROM idempotency_keys"
                                + " WHERE customer_id = ? AND operation = ? AND idempotency_key = ?",
                        customerId, CREATE_OPERATION, idempotencyKey)
                .stream()
                .findFirst();
        if (previous.isPresent()) {
            return new Issued(replay(customerId, requestHash, previous.get()), correlationId);
        }

        // PostgreSQL stores microseconds; truncate so a replay returns the identical expiry.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        BigDecimal rate = RateConvention.targetPerSource(request.source(), request.target(),
                rates.madPerEur(request.source(), request.target()));
        FxPricing.Breakdown price = FxPricing.price(request.source(), request.target(),
                request.sourceAmountMinor(), rate, QuotePolicy.fees(request.source()));
        if (price.targetAmountMinor() <= 0) {
            throw ApiProblemException.unprocessable();
        }
        QuoteRecord quote = new QuoteRecord(UUID.randomUUID(), customerId, request.source(), request.target(),
                price.sourceAmountMinor(), price.feeSourceMinor(), price.feeFixedMinor(), price.totalDebitMinor(),
                price.targetAmountMinor(), price.rate(), rates.rateSource(), now, now.plus(QuotePolicy.TTL),
                QuoteRecord.ISSUED);
        repository.insert(quote);
        jdbc.update("INSERT INTO idempotency_keys (customer_id, operation, idempotency_key, request_sha256,"
                        + " resource_id) VALUES (?, ?, ?, ?, ?)",
                customerId, CREATE_OPERATION, idempotencyKey, requestHash, quote.id());
        audit(customerId, quote, correlationId);
        return new Issued(Quote.from(quote, now), correlationId);
    }

    /**
     * Locks and returns the caller's quote if it can still be used; for the transfer workflow
     * (FX-008), which must consume it within the same transaction. Must run inside an existing
     * transaction so the row lock is held until consumption.
     *
     * @throws ApiProblemException {@code 404} if unknown or owned by another customer, {@code 409}
     *     if already consumed (or in any unrecognized state), {@code 422} if expired (stale)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public QuoteRecord requireFreshForUpdate(UUID customerId, UUID quoteId) {
        requireSimulator();
        QuoteRecord quote = repository.lockOwned(customerId, quoteId).orElseThrow(ApiProblemException::notFound);
        String status = quote.effectiveStatus(clock.instant());
        if (QuoteRecord.EXPIRED.equals(status)) {
            throw ApiProblemException.unprocessable();
        }
        if (!QuoteRecord.ISSUED.equals(status)) {
            throw ApiProblemException.conflict();
        }
        return quote;
    }

    private void requireSimulator() {
        if (!SimulatedFxRateProvider.NAME.equals(rates.name())
                || !SimulatedFxRateProvider.RATE_SOURCE.equals(rates.rateSource())) {
            throw new IllegalStateException("Only the simulated FX rate provider is permitted in SIMULATION mode");
        }
    }

    private void lockEligibleCustomer(UUID customerId) {
        jdbc.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'");
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT status, kyc_status FROM customers WHERE id = ? FOR UPDATE", customerId);
        } catch (DataAccessException e) {
            if (isLockTimeout(e)) {
                throw ApiProblemException.serviceUnavailable();
            }
            throw e;
        }
        if (rows.isEmpty()) {
            throw ApiProblemException.notFound();
        }
        // Re-checked under the lock: eligibility may have been revoked since the request began.
        if (!ACTIVE.equals(rows.get(0).get("status")) || !KYC_APPROVED.equals(rows.get(0).get("kyc_status"))) {
            throw ApiProblemException.unprocessable();
        }
    }

    private static boolean isLockTimeout(DataAccessException e) {
        if (e instanceof PessimisticLockingFailureException) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private Quote replay(UUID customerId, String requestHash, Map<String, Object> previous) {
        if (!MessageDigest.isEqual(requestHash.getBytes(StandardCharsets.US_ASCII),
                String.valueOf(previous.get("request_sha256")).getBytes(StandardCharsets.US_ASCII))) {
            throw ApiProblemException.conflict();
        }
        if (!(previous.get("resource_id") instanceof UUID resourceId)) {
            throw ApiProblemException.conflict();
        }
        QuoteRecord quote = repository.findOwned(customerId, resourceId).orElseThrow(ApiProblemException::conflict);
        return Quote.from(quote, clock.instant());
    }

    /** SHA-256 over a canonical JSON rendering of the validated, normalized request. */
    String requestHash(QuoteRules.ValidRequest request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sourceCurrency", request.source().name());
        canonical.put("targetCurrency", request.target().name());
        canonical.put("sourceAmountMinor", Long.toString(request.sourceAmountMinor()));
        canonical.put("purpose", request.purpose());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JacksonException e) {
            throw new IllegalStateException("Unable to hash quote request", e);
        }
    }

    private void audit(UUID customerId, QuoteRecord quote, UUID correlationId) {
        // Enum values and labels only; amounts stay in the quote row itself.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceCurrency", quote.sourceCurrency().name());
        metadata.put("targetCurrency", quote.targetCurrency().name());
        metadata.put("rateSource", quote.rateSource());
        metadata.put("mode", Quote.MODE);
        metadata.put("correlationId", correlationId.toString());
        try {
            jdbc.update("INSERT INTO audit_events (actor_type, actor_ref, action, resource_type, resource_id,"
                            + " safe_metadata) VALUES ('CUSTOMER', ?, 'QUOTE_ISSUED', 'FX_QUOTE', ?, ?::jsonb)",
                    customerId.toString(), quote.id().toString(), mapper.writeValueAsString(metadata));
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to serialise audit metadata", e);
        }
    }
}
