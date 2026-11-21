package com.eurmad.api.quote;

import java.util.Optional;

/**
 * ISO 4217 currencies supported by the simulation, with the number of fractional digits of their
 * minor unit. Money is always an integer count of minor units paired with one of these codes.
 */
public enum CurrencyCode {
    EUR(2),
    MAD(2);

    private final int minorDigits;

    CurrencyCode(int minorDigits) {
        this.minorDigits = minorDigits;
    }

    public int minorDigits() {
        return minorDigits;
    }

    /** The currency for an exact code; empty for anything else (no case folding or trimming). */
    public static Optional<CurrencyCode> parse(String code) {
        for (CurrencyCode currency : values()) {
            if (currency.name().equals(code)) {
                return Optional.of(currency);
            }
        }
        return Optional.empty();
    }
}
