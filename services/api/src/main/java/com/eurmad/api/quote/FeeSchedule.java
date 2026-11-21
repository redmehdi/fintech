package com.eurmad.api.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fee charged in the <b>source</b> currency: a fixed amount plus basis points of the source amount,
 * the variable part rounded {@link RoundingMode#HALF_EVEN} to the source minor unit.
 *
 * @param fixedMinor fixed fee in source minor units, {@code >= 0}
 * @param basisPoints variable fee in 1/10,000 of the source amount, {@code 0..10_000}
 */
public record FeeSchedule(long fixedMinor, int basisPoints) {

    static final long BASIS_POINTS_PER_UNIT = 10_000L;

    public FeeSchedule {
        if (fixedMinor < 0) {
            throw new IllegalArgumentException("Fixed fee must not be negative");
        }
        if (basisPoints < 0 || basisPoints > BASIS_POINTS_PER_UNIT) {
            throw new IllegalArgumentException("Basis points must be between 0 and 10000");
        }
    }

    /** Fee in source minor units for a positive source amount; exact integer arithmetic throughout. */
    public long feeMinor(long sourceAmountMinor) {
        if (sourceAmountMinor <= 0) {
            throw new IllegalArgumentException("Source amount must be positive");
        }
        BigDecimal variable = BigDecimal.valueOf(sourceAmountMinor)
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(BASIS_POINTS_PER_UNIT), 0, RoundingMode.HALF_EVEN);
        return Math.addExact(fixedMinor, variable.longValueExact());
    }
}
