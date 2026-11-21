package com.eurmad.api.kyc;

import com.eurmad.api.kyc.VerificationDecision.Outcome;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic local mock of an identity-verification provider. Performs no network I/O, collects
 * no documents and decides synchronously with the configured {@link KycSandboxProperties#outcome()}.
 */
@Component
public class SandboxIdentityVerificationProvider implements IdentityVerificationProvider {

    public static final String NAME = "SANDBOX";
    static final String REJECTION_REASON = "SANDBOX_SIMULATED_REJECTION";

    private final Outcome outcome;

    public SandboxIdentityVerificationProvider(KycSandboxProperties properties) {
        this.outcome = Objects.requireNonNull(properties.outcome(), "fintech.kyc.sandbox.outcome");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public VerificationDecision startVerification(UUID kycCaseId) {
        Objects.requireNonNull(kycCaseId, "kycCaseId");
        Outcome decided = configuredOutcome();
        String reason = decided == Outcome.REJECTED ? REJECTION_REASON : null;
        return new VerificationDecision("sandbox-kyc:" + kycCaseId, decided, reason);
    }

    /** The configured deterministic outcome; a seam for tests to exercise each sandbox branch. */
    Outcome configuredOutcome() {
        return outcome;
    }
}
