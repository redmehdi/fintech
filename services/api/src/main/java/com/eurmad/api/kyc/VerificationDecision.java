package com.eurmad.api.kyc;

import java.util.Objects;

/**
 * Outcome reported by an {@link IdentityVerificationProvider}. Holds only the provider's opaque case
 * reference, the decision and an optional reason code; never document data or extracted PII.
 */
public record VerificationDecision(String providerCaseRef, Outcome outcome, String reasonCode) {

    public enum Outcome {
        PENDING,
        APPROVED,
        REJECTED;

        KycStatus toKycStatus() {
            return KycStatus.valueOf(name());
        }
    }

    public VerificationDecision {
        Objects.requireNonNull(outcome, "outcome");
        if (providerCaseRef == null || providerCaseRef.isBlank() || providerCaseRef.length() > 128) {
            throw new IllegalArgumentException("providerCaseRef must be 1-128 characters");
        }
        if (reasonCode != null && !reasonCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("reasonCode must be an upper-case code");
        }
    }
}
