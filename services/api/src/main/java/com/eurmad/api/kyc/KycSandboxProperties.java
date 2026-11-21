package com.eurmad.api.kyc;

import com.eurmad.api.kyc.VerificationDecision.Outcome;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deterministic sandbox KYC behaviour bound from {@code fintech.kyc.sandbox.*}. The outcome applies to
 * every synthetic verification; it never reaches a real provider.
 *
 * <p>Only decisive outcomes ({@code APPROVED}, {@code REJECTED}) may be configured. A configured
 * {@code PENDING} would leave every case undecided with no way to resolve it, because the signed
 * provider callback that decides pending cases is not available yet (webhook ingestion, FX-009).
 * Startup fails rather than locking customers out.
 */
@ConfigurationProperties(prefix = "fintech.kyc.sandbox")
public record KycSandboxProperties(@DefaultValue("APPROVED") Outcome outcome) {

    public KycSandboxProperties {
        if (outcome == Outcome.PENDING) {
            throw new IllegalArgumentException(
                    "fintech.kyc.sandbox.outcome=PENDING is not supported until a signed sandbox callback exists");
        }
    }
}
