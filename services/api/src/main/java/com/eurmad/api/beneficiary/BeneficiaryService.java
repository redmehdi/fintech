package com.eurmad.api.beneficiary;

import com.eurmad.api.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Beneficiary workflow (FX-006). Every operation is scoped to the authenticated customer's ID.
 *
 * <p>Creation locks the customer row, so concurrent requests from one customer are serialized;
 * the idempotency record, the beneficiary and the audit event are written in one transaction. The
 * lock wait is bounded by a transaction-local {@code lock_timeout}, so a held lock cannot pin pool
 * connections indefinitely; on timeout the transaction rolls back and the caller may retry.
 * Audit metadata never contains the display name, destination token or masked destination.
 */
@Service
public class BeneficiaryService {

    static final String CREATE_OPERATION = "CREATE_BENEFICIARY";
    private static final String ACTIVE = "ACTIVE";
    /** Upper bound on waiting for the customer row lock; a literal because SET cannot be bound. */
    static final String LOCK_TIMEOUT = "2s";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcTemplate jdbc;
    private final BeneficiaryRepository repository;
    private final ObjectMapper mapper;
    private final DestinationTokenRegistry tokens;

    BeneficiaryService(JdbcTemplate jdbc, BeneficiaryRepository repository, ObjectMapper mapper,
            DestinationTokenRegistry tokens) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.mapper = mapper;
        this.tokens = tokens;
    }

    /** Outcome of a create request and the server-generated correlation ID of this request. */
    public record Created(Beneficiary beneficiary, UUID correlationId) {}

    @Transactional(readOnly = true)
    public List<Beneficiary> list(UUID customerId) {
        return repository.listActive(customerId);
    }

    @Transactional(readOnly = true)
    public Beneficiary get(UUID customerId, UUID beneficiaryId) {
        return repository.findActiveOwned(customerId, beneficiaryId).orElseThrow(ApiProblemException::notFound);
    }

    /**
     * @throws ApiProblemException {@code 503} if the customer row lock is not acquired within
     *     {@link #LOCK_TIMEOUT}; nothing has been written.
     */
    @Transactional
    public Created create(UUID customerId, String idempotencyKey, BeneficiaryRules.ValidCreate request) {
        // Only tokens the trusted issuer confirms for this country and payout method are accepted.
        if (!tokens.isIssued(request.destinationTokenRef(), request.country(), request.payoutMethod())) {
            throw ApiProblemException.unprocessable();
        }
        UUID correlationId = UUID.randomUUID();
        jdbc.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'");
        List<String> status;
        try {
            status = jdbc.queryForList("SELECT status FROM customers WHERE id = ? FOR UPDATE", String.class, customerId);
        } catch (DataAccessException e) {
            if (isLockTimeout(e)) {
                // Nothing written yet; the transaction rolls back and a retry is safe.
                throw ApiProblemException.serviceUnavailable();
            }
            throw e;
        }
        if (status.isEmpty()) {
            throw ApiProblemException.notFound();
        }
        if (!ACTIVE.equals(status.get(0))) {
            throw ApiProblemException.unprocessable();
        }

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

        UUID id = UUID.randomUUID();
        String masked = BeneficiaryRules.mask(request.destinationTokenRef());
        repository.insert(id, customerId, request, masked);
        // The customer row lock serializes this customer's creates; the unique constraint remains
        // the last line of defence and fails the whole transaction on a race.
        jdbc.update("INSERT INTO idempotency_keys (customer_id, operation, idempotency_key, request_sha256,"
                        + " resource_id) VALUES (?, ?, ?, ?, ?)",
                customerId, CREATE_OPERATION, idempotencyKey, requestHash, id);
        audit(customerId, "BENEFICIARY_CREATED", id, correlationId, request.country(), request.payoutMethod());
        return new Created(new Beneficiary(id, request.displayName(), request.country(), request.payoutMethod(),
                masked), correlationId);
    }

    /** PostgreSQL {@code lock_not_available} (55P03), raised when {@code lock_timeout} expires. */
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

    private Beneficiary replay(UUID customerId, String requestHash, Map<String, Object> previous) {
        if (!MessageDigest.isEqual(requestHash.getBytes(StandardCharsets.US_ASCII),
                String.valueOf(previous.get("request_sha256")).getBytes(StandardCharsets.US_ASCII))) {
            throw ApiProblemException.conflict();
        }
        if (!(previous.get("resource_id") instanceof UUID resourceId)) {
            throw ApiProblemException.conflict();
        }
        // A deleted beneficiary stays unavailable, even to an identical replay.
        return repository.findActiveOwned(customerId, resourceId).orElseThrow(ApiProblemException::conflict);
    }

    /** Returns the correlation ID recorded in the audit event. */
    @Transactional
    public UUID delete(UUID customerId, UUID beneficiaryId) {
        Beneficiary deleted = repository.softDelete(customerId, beneficiaryId)
                .orElseThrow(ApiProblemException::notFound);
        UUID correlationId = UUID.randomUUID();
        audit(customerId, "BENEFICIARY_DELETED", deleted.id(), correlationId, deleted.country(),
                deleted.payoutMethod());
        return correlationId;
    }

    /** SHA-256 over a canonical JSON rendering of the validated, normalized request. */
    String requestHash(BeneficiaryRules.ValidCreate request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("displayName", request.displayName());
        canonical.put("country", request.country());
        canonical.put("payoutMethod", request.payoutMethod());
        canonical.put("destinationTokenRef", request.destinationTokenRef());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JacksonException e) {
            throw new IllegalStateException("Unable to hash beneficiary request", e);
        }
    }

    private void audit(UUID customerId, String action, UUID beneficiaryId, UUID correlationId, String country,
            String payoutMethod) {
        // Enum values only; never the display name, token or masked destination.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("country", country);
        metadata.put("payoutMethod", payoutMethod);
        metadata.put("mode", "SIMULATION");
        metadata.put("correlationId", correlationId.toString());
        try {
            jdbc.update("INSERT INTO audit_events (actor_type, actor_ref, action, resource_type, resource_id,"
                            + " safe_metadata) VALUES ('CUSTOMER', ?, ?, 'BENEFICIARY', ?, ?::jsonb)",
                    customerId.toString(), action, beneficiaryId.toString(), mapper.writeValueAsString(metadata));
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to serialise audit metadata", e);
        }
    }
}
