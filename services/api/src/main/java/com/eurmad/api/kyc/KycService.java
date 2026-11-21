package com.eurmad.api.kyc;

import com.eurmad.api.error.ApiProblemException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KYC sandbox workflow (FX-005).
 *
 * <p>Starting a verification locks the customer row, applies the explicit transition rule in {@link
 * KycStatus#canStartVerification()}, records the case and the provider decision, and appends audit
 * events, all in one transaction. Only case metadata is persisted; never documents or PII.
 */
@Service
public class KycService {

    private static final String ACTIVE = "ACTIVE";

    private final JdbcTemplate jdbc;
    private final IdentityVerificationProvider provider;
    private final ObjectMapper mapper;

    KycService(JdbcTemplate jdbc, IdentityVerificationProvider provider, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.mapper = mapper;
    }

    /**
     * A recorded verification case, its current status and the server-generated correlation ID of
     * the request that started it (recorded in every audit event of that request).
     */
    public record KycCase(UUID id, VerificationDecision.Outcome status, UUID correlationId) {}

    @Transactional
    public KycCase startVerification(UUID customerId) {
        // Fail closed on anything but the sandbox provider (also enforced by a DB constraint).
        if (!SandboxIdentityVerificationProvider.NAME.equals(provider.name())) {
            throw new IllegalStateException("Only the sandbox KYC provider is permitted in SIMULATION mode");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT kyc_status, status FROM customers WHERE id = ? FOR UPDATE", customerId);
        if (rows.isEmpty()) {
            throw ApiProblemException.notFound();
        }
        if (!ACTIVE.equals(rows.get(0).get("status"))) {
            throw ApiProblemException.unprocessable();
        }
        KycStatus previous = KycStatus.parse((String) rows.get(0).get("kyc_status"))
                .orElseThrow(ApiProblemException::conflict);
        if (!previous.canStartVerification()) {
            throw ApiProblemException.conflict();
        }

        UUID caseId = UUID.randomUUID();
        // Server-generated, never taken from the request, so clients cannot forge audit linkage.
        UUID correlationId = UUID.randomUUID();
        VerificationDecision decision = provider.startVerification(caseId);
        if (decision == null) {
            throw new IllegalStateException("KYC provider returned no decision");
        }
        KycStatus next = decision.outcome().toKycStatus();
        boolean decided = decision.outcome() != VerificationDecision.Outcome.PENDING;

        try {
            jdbc.update(
                    "INSERT INTO kyc_cases (id, customer_id, provider, provider_case_ref, status,"
                            + " decision_reason_code, decided_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END)",
                    caseId, customerId, provider.name(), decision.providerCaseRef(), next.name(),
                    decision.reasonCode(), decided);
        } catch (DuplicateKeyException e) {
            throw ApiProblemException.conflict();
        }
        int updated = jdbc.update(
                "UPDATE customers SET kyc_status = ?, updated_at = now() WHERE id = ? AND kyc_status = ?",
                next.name(), customerId, previous.name());
        if (updated != 1) {
            throw ApiProblemException.conflict();
        }

        audit("CUSTOMER", customerId.toString(), "KYC_VERIFICATION_STARTED", caseId, correlationId,
                previous, KycStatus.PENDING, null);
        if (decided) {
            audit("PROVIDER", provider.name(), "KYC_DECISION_RECORDED", caseId, correlationId,
                    KycStatus.PENDING, next, decision.reasonCode());
        }
        return new KycCase(caseId, decision.outcome(), correlationId);
    }

    private void audit(String actorType, String actorRef, String action, UUID caseId,
            UUID correlationId, KycStatus previous, KycStatus next, String reasonCode) {
        // Metadata holds enum names and reason codes only; never PII or provider payloads.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("previousStatus", previous.name());
        metadata.put("nextStatus", next.name());
        metadata.put("provider", provider.name());
        metadata.put("mode", "SIMULATION");
        metadata.put("correlationId", correlationId.toString());
        if (reasonCode != null) {
            metadata.put("reasonCode", reasonCode);
        }
        try {
            jdbc.update(
                    "INSERT INTO audit_events (actor_type, actor_ref, action, resource_type, resource_id,"
                            + " safe_metadata) VALUES (?, ?, ?, 'KYC_CASE', ?, ?::jsonb)",
                    actorType, actorRef, action, caseId.toString(), mapper.writeValueAsString(metadata));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise audit metadata", e);
        }
    }
}
