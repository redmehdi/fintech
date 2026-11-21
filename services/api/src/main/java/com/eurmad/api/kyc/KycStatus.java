package com.eurmad.api.kyc;

import java.util.Arrays;
import java.util.Optional;

/** Customer-level KYC status ({@code customers.kyc_status}; contract {@code KycStatus.status}). */
public enum KycStatus {
    NOT_STARTED,
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED;

    /**
     * Explicit transition rule for starting a sandbox verification. {@code REJECTED} is excluded on
     * purpose: re-verification after a rejection requires an operator decision (FX-016).
     */
    public boolean canStartVerification() {
        return this == NOT_STARTED || this == EXPIRED;
    }

    static Optional<KycStatus> parse(String value) {
        return Arrays.stream(values()).filter(s -> s.name().equals(value)).findFirst();
    }
}
