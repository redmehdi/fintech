package com.eurmad.api.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Centralized quote arithmetic (FX-007). Pure and deterministic; no {@code double} anywhere.
 *
 * <ul>
 *   <li>{@code targetAmountMinor = HALF_EVEN(sourceAmountMinor × rate × 10^(targetDigits − sourceDigits))}
 *   <li>{@code feeSourceMinor = fixed + HALF_EVEN(sourceAmountMinor × bps / 10,000)} from the
 *       {@link FeeSchedule}, in the source currency (only the variable part is rounded)
 *   <li>{@code totalDebitMinor = sourceAmountMinor + feeSourceMinor} (overflow-checked)
 * </ul>
 */
public final class FxPricing {

    /** Rounding applied to the converted amount (PRD §7). */
    static final RoundingMode AMOUNT_ROUNDING = RoundingMode.HALF_EVEN;

    private FxPricing() {}

    /**
     * Integer minor-unit breakdown of a priced quote. {@code feeFixedMinor} is the fixed component
     * of {@code feeSourceMinor}; the variable component is {@code feeSourceMinor - feeFixedMinor}.
     */
    public record Breakdown(long sourceAmountMinor, long feeSourceMinor, long feeFixedMinor, long totalDebitMinor,
            long targetAmountMinor, BigDecimal rate) {}

    /**
     * @param rate target currency per one source currency
     * @throws IllegalArgumentException for equal currencies, a non-positive amount or invalid rate
     * @throws ArithmeticException if any result overflows a {@code long}
     */
    public static Breakdown price(CurrencyCode source, CurrencyCode target, long sourceAmountMinor,
            BigDecimal rate, FeeSchedule fees) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(fees, "fees");
        if (source == target) {
            throw new IllegalArgumentException("Source and target currency must differ");
        }
        if (sourceAmountMinor <= 0) {
            throw new IllegalArgumentException("Source amount must be positive");
        }
        BigDecimal normalizedRate = RateConvention.normalize(rate);
        long targetMinor = convert(source, target, sourceAmountMinor, normalizedRate);
        long feeMinor = fees.feeMinor(sourceAmountMinor);
        long totalDebit = Math.addExact(sourceAmountMinor, feeMinor);
        return new Breakdown(sourceAmountMinor, feeMinor, fees.fixedMinor(), totalDebit, targetMinor, normalizedRate);
    }

    /** Converts source minor units into target minor units at {@code rate}, rounded HALF_EVEN. */
    static long convert(CurrencyCode source, CurrencyCode target, long sourceAmountMinor, BigDecimal rate) {
        return BigDecimal.valueOf(sourceAmountMinor)
                .multiply(rate)
                .movePointRight(target.minorDigits() - source.minorDigits())
                .setScale(0, AMOUNT_ROUNDING)
                .longValueExact();
    }
}
