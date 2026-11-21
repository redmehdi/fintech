package com.eurmad.api.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * FX rate conventions (FX-007).
 *
 * <ul>
 *   <li>A quote rate is <b>target currency per one source currency</b> ({@code target / source}),
 *       stored as {@code NUMERIC(22,12)} and always carried at scale {@link #RATE_SCALE}.
 *   <li>The canonical market convention is <b>MAD per one EUR</b>. For EUR→MAD the quote rate equals
 *       the canonical rate; for MAD→EUR it is its reciprocal, rounded {@link RoundingMode#DOWN} to
 *       {@link #RATE_SCALE} digits so the quoted rate never overstates what the source buys.
 * </ul>
 */
public final class RateConvention {

    /** Fractional digits of every rate (matches {@code NUMERIC(22,12)} and the contract pattern). */
    public static final int RATE_SCALE = 12;
    /** Total significant digits allowed by {@code NUMERIC(22,12)}. */
    static final int RATE_PRECISION = 22;

    private RateConvention() {}

    /**
     * Converts a canonical MAD-per-EUR rate into the target-per-source rate for the given pair.
     *
     * @throws IllegalArgumentException for equal currencies or a non-positive or over-precise rate
     */
    public static BigDecimal targetPerSource(CurrencyCode source, CurrencyCode target, BigDecimal madPerEur) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        BigDecimal canonical = normalize(madPerEur);
        if (source == CurrencyCode.EUR && target == CurrencyCode.MAD) {
            return canonical;
        }
        if (source == CurrencyCode.MAD && target == CurrencyCode.EUR) {
            BigDecimal inverse = BigDecimal.ONE.divide(canonical, RATE_SCALE, RoundingMode.DOWN);
            if (inverse.signum() <= 0) {
                throw new IllegalArgumentException("Inverse rate underflows the rate scale");
            }
            return inverse;
        }
        throw new IllegalArgumentException("Unsupported currency pair");
    }

    /**
     * Returns the rate at exactly {@link #RATE_SCALE}; rejects zero, negative, null and any rate
     * that would need rounding or does not fit {@code NUMERIC(22,12)}. Never rounds silently.
     */
    public static BigDecimal normalize(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        BigDecimal scaled;
        try {
            scaled = rate.setScale(RATE_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Rate has more than " + RATE_SCALE + " fractional digits", e);
        }
        if (scaled.precision() > RATE_PRECISION) {
            throw new IllegalArgumentException("Rate exceeds NUMERIC(22,12)");
        }
        return scaled;
    }
}
