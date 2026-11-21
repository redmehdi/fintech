package com.eurmad.api.transfer;

import com.eurmad.api.config.FintechProperties;
import com.eurmad.api.config.OperatingMode;
import com.eurmad.api.error.ApiProblemException;
import com.eurmad.api.quote.QuoteRecord;
import com.eurmad.api.quote.QuoteService;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Transfer orchestration (FX-008). SIMULATION ONLY: no funds are held, moved or exchanged, and no
 * provider instruction is sent; a created transfer waits in {@code AWAITING_FUNDING} for simulated
 * provider events (FX-009).
 *
 * <p>Creation locks the customer row (every lock bounded by a transaction-local {@code
 * lock_timeout}), so concurrent requests from one customer are serialized; it answers idempotent
 * replays, then checks eligibility under that lock, locks the quote and (shared) the beneficiary,
 * consumes the quote, and writes the transfer, its lifecycle events, the idempotency record and an
 * audit event in one transaction. Lock order is always customer, then quote, then beneficiary.
 */
@Service
public class TransferService {

    static final String CREATE_OPERATION = "CREATE_TRANSFER";
    static final String LOCK_TIMEOUT = "2s";
    static final String SYSTEM_ACTOR = "transfer-orchestrator";
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String ACTIVE = "ACTIVE";
    private static final String KYC_APPROVED = "APPROVED";

    /** Who caused a lifecycle transition; values of {@code transaction_events.actor_type}. */
    public enum ActorType { CUSTOMER, SYSTEM, PROVIDER, OPERATOR }

    private final JdbcTemplate jdbc;
    private final TransferRepository repository;
    private final QuoteService quotes;
    private final FintechProperties fintech;
    private final ObjectMapper mapper;
    private final Clock clock;

    TransferService(JdbcTemplate jdbc, TransferRepository repository, QuoteService quotes,
            FintechProperties fintech, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.quotes = quotes;
        this.fintech = fintech;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Outcome of a create request and the server-generated correlation ID of this request. */
    public record Created(Transfer transfer, UUID correlationId) {}

    @Transactional(readOnly = true)
    public List<Transfer> list(UUID customerId) {
        return repository.listOwned(customerId).stream().map(Transfer::from).toList();
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID customerId, UUID transferId) {
        return repository.findOwned(customerId, transferId).map(Transfer::from)
                .orElseThrow(ApiProblemException::notFound);
    }

    /**
     * An idempotent replay is answered before eligibility is enforced, so a request that committed
     * but whose response was lost still gets its transfer (or {@code 409} for a changed payload)
     * after the account is suspended or KYC expires.
     *
     * @throws ApiProblemException {@code 404} unknown/foreign quote or beneficiary; {@code 409}
     *     idempotency conflict or quote already consumed; {@code 422} ineligible customer, expired
     *     quote or beneficiary outside the quote's payout corridor; {@code 503} a lock (customer,
     *     quote, beneficiary) was not acquired within {@link #LOCK_TIMEOUT}; nothing is written
     */
    @Transactional
    public Created create(UUID customerId, String idempotencyKey, TransferRules.ValidCreate request) {
        requireSimulation();
        try {
            return createLocked(customerId, idempotencyKey, request);
        } catch (DataAccessException e) {
            if (isLockTimeout(e)) {
                // The transaction rolls back; a retry with the same key is safe.
                throw ApiProblemException.serviceUnavailable();
            }
            throw e;
        }
    }

    private Created createLocked(UUID customerId, String idempotencyKey, TransferRules.ValidCreate request) {
        UUID correlationId = UUID.randomUUID();
        Map<String, Object> customer = lockCustomer(customerId);

        String requestHash = requestHash(request);
        Optional<Map<String, Object>> previous = jdbc.queryForList(
                        "SELECT request_sha256, resource_id FROM idempotency_keys"
                                + " WHERE customer_id = ? AND operation = ? AND idempotency_key = ?",
                        customerId, CREATE_OPERATION, idempotencyKey)
                .stream()
                .findFirst();
        if (previous.isPresent()) {
            return new Created(replay(customerId, requestHash, previous.get()), correlationId);
        }
        // A new transfer: eligibility is checked under the customer lock, since it may have been
        // revoked since the request began.
        if (!ACTIVE.equals(customer.get("status")) || !KYC_APPROVED.equals(customer.get("kyc_status"))) {
            throw ApiProblemException.unprocessable();
        }

        QuoteRecord quote = quotes.requireFreshForUpdate(customerId, request.quoteId());
        String beneficiaryCountry = repository.lockActiveBeneficiaryCountry(customerId, request.beneficiaryId())
                .orElseThrow(ApiProblemException::notFound);
        String requiredCountry = TransferRules.payoutCountry(quote.targetCurrency())
                .orElseThrow(ApiProblemException::unprocessable);
        if (!requiredCountry.equals(beneficiaryCountry)) {
            throw ApiProblemException.unprocessable();
        }
        quotes.consume(customerId, quote.id());

        // PostgreSQL stores microseconds; truncate so a replay returns identical timestamps.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID id = UUID.randomUUID();
        try {
            repository.insertCreated(id, customerId, quote.id(), request.beneficiaryId(), request.purpose(), now);
        } catch (DataIntegrityViolationException e) {
            // Defence in depth: the quote and beneficiary are already locked, so the insert trigger's
            // re-validation is not expected to fail.
            throw ApiProblemException.conflict();
        }
        recordEvent(id, null, TransferStatus.CREATED, ActorType.CUSTOMER, customerId.toString(),
                "TRANSFER_REQUESTED", correlationId);
        // Eligibility (ACTIVE, KYC APPROVED) was verified under the customer lock above.
        if (!repository.transition(id, TransferStatus.CREATED, 0, TransferStatus.AWAITING_FUNDING, now)) {
            throw new IllegalStateException("Newly created transfer changed concurrently");
        }
        recordEvent(id, TransferStatus.CREATED, TransferStatus.AWAITING_FUNDING, ActorType.SYSTEM, SYSTEM_ACTOR,
                "ELIGIBILITY_PASSED", correlationId);

        jdbc.update("INSERT INTO idempotency_keys (customer_id, operation, idempotency_key, request_sha256,"
                        + " resource_id) VALUES (?, ?, ?, ?, ?)",
                customerId, CREATE_OPERATION, idempotencyKey, requestHash, id);
        audit(customerId, id, quote, request.purpose(), correlationId);
        TransferRecord created = new TransferRecord(id, customerId, quote.id(), request.beneficiaryId(), request.purpose(),
                TransferStatus.AWAITING_FUNDING, 1, now, now);
        return new Created(Transfer.from(created), correlationId);
    }

    /** Status and version of a transfer as observed by a workflow component before it decides. */
    public record Observed(TransferStatus status, long version) {}

    /**
     * Current status and version of a transfer, for workflow components (FX-009+) to base a
     * decision on and pass back to {@link #transition}. Not owner-scoped (system use).
     */
    @Transactional(readOnly = true)
    public Optional<Observed> observe(UUID transferId) {
        return repository.findById(transferId).map(t -> new Observed(t.status(), t.version()));
    }

    /**
     * Applies one state-machine transition and records it as a lifecycle event, for workflow
     * components (provider events, operators, FX-009+). Runs in the caller's transaction and locks
     * the transfer row until it ends.
     *
     * @param expected the state and version the caller's decision was based on (see {@link
     *     #observe}); any difference is a {@code 409}, so a stale decision is never applied, even if
     *     the transfer has since left and returned to the same status
     * @throws ApiProblemException {@code 404} unknown transfer, {@code 409} stale or disallowed transition
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Transfer transition(UUID transferId, Observed expected, TransferStatus target, ActorType actorType,
            String actorRef, String reasonCode, UUID correlationId) {
        requireSimulation();
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(correlationId, "correlationId");
        if (actorRef == null || actorRef.isBlank()) {
            throw new IllegalArgumentException("actorRef is required");
        }
        TransferRecord current = repository.lockById(transferId).orElseThrow(ApiProblemException::notFound);
        if (current.status() != expected.status() || current.version() != expected.version()
                || !expected.status().canTransitionTo(target)) {
            throw ApiProblemException.conflict();
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!repository.transition(transferId, expected.status(), expected.version(), target, now)) {
            throw ApiProblemException.conflict();
        }
        recordEvent(transferId, expected.status(), target, actorType, actorRef, reasonCode, correlationId);
        return Transfer.from(new TransferRecord(current.id(), current.customerId(), current.quoteId(),
                current.beneficiaryId(), current.purpose(), target, current.version() + 1, current.createdAt(), now));
    }

    /** Fails closed if the process is not in simulation mode (also enforced at startup). */
    private void requireSimulation() {
        if (fintech.mode() != OperatingMode.SIMULATION || fintech.liveEnabled()) {
            throw new IllegalStateException("Transfers are available in SIMULATION mode only");
        }
    }

    /**
     * Locks the customer row, serializing this customer's creates, and returns its status and KYC
     * status. The transaction-local {@code lock_timeout} bounds every lock taken afterwards too.
     */
    private Map<String, Object> lockCustomer(UUID customerId) {
        jdbc.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'");
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT status, kyc_status FROM customers WHERE id = ? FOR UPDATE", customerId);
        if (rows.isEmpty()) {
            throw ApiProblemException.notFound();
        }
        return rows.get(0);
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

    private Transfer replay(UUID customerId, String requestHash, Map<String, Object> previous) {
        if (!MessageDigest.isEqual(requestHash.getBytes(StandardCharsets.US_ASCII),
                String.valueOf(previous.get("request_sha256")).getBytes(StandardCharsets.US_ASCII))) {
            throw ApiProblemException.conflict();
        }
        if (!(previous.get("resource_id") instanceof UUID resourceId)) {
            throw ApiProblemException.conflict();
        }
        // The original transfer in its current state; never a second transfer.
        return repository.findOwned(customerId, resourceId).map(Transfer::from)
                .orElseThrow(ApiProblemException::conflict);
    }

    /** SHA-256 over a canonical JSON rendering of the validated, normalized request. */
    String requestHash(TransferRules.ValidCreate request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("quoteId", request.quoteId().toString());
        canonical.put("beneficiaryId", request.beneficiaryId().toString());
        canonical.put("purpose", request.purpose());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JacksonException e) {
            throw new IllegalStateException("Unable to hash transfer request", e);
        }
    }

    private void recordEvent(UUID transferId, TransferStatus previous, TransferStatus next, ActorType actorType,
            String actorRef, String reasonCode, UUID correlationId) {
        jdbc.update("INSERT INTO transaction_events (resource_kind, resource_id, previous_status, next_status,"
                        + " actor_type, actor_ref, reason_code, correlation_id) VALUES ('TRANSFER', ?, ?, ?, ?, ?, ?, ?)",
                transferId, previous == null ? null : previous.name(), next.name(), actorType.name(), actorRef,
                reasonCode, correlationId);
    }

    private void audit(UUID customerId, UUID transferId, QuoteRecord quote, String purpose, UUID correlationId) {
        // Enum values and labels only; no amounts, beneficiary or destination data.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceCurrency", quote.sourceCurrency().name());
        metadata.put("targetCurrency", quote.targetCurrency().name());
        metadata.put("purpose", purpose);
        metadata.put("status", TransferStatus.AWAITING_FUNDING.name());
        metadata.put("mode", Transfer.MODE);
        metadata.put("correlationId", correlationId.toString());
        try {
            jdbc.update("INSERT INTO audit_events (actor_type, actor_ref, action, resource_type, resource_id,"
                            + " safe_metadata) VALUES ('CUSTOMER', ?, 'TRANSFER_CREATED', 'TRANSFER', ?, ?::jsonb)",
                    customerId.toString(), transferId.toString(), mapper.writeValueAsString(metadata));
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to serialise audit metadata", e);
        }
    }
}
