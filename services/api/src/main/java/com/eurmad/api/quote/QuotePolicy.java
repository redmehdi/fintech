package com.eurmad.api.quote;

import java.time.Duration;
import java.util.Map;

/**
 * Simulation pricing policy: fees, per-currency amount limits and quote lifetime. Values are
 * synthetic placeholders pending partner tariffs (PRD §11) and are not configurable at runtime.
 */
final class QuotePolicy {

    /** Server-side quote lifetime; the database caps it at 30 minutes. */
    static final Duration TTL = Duration.ofMinutes(10);

    private static final Map<CurrencyCode, FeeSchedule> FEES = Map.of(
            CurrencyCode.EUR, new FeeSchedule(199, 50),   // EUR 1.99 + 0.50 %
            CurrencyCode.MAD, new FeeSchedule(2_000, 50)); // MAD 20.00 + 0.50 %

    /** Inclusive source amount bounds in minor units. */
    record Limits(long minMinor, long maxMinor) {}

    private static final Map<CurrencyCode, Limits> LIMITS = Map.of(
            CurrencyCode.EUR, new Limits(100, 1_000_000),      // EUR 1.00 .. 10,000.00
            CurrencyCode.MAD, new Limits(1_000, 10_000_000));  // MAD 10.00 .. 100,000.00

    private QuotePolicy() {}

    static FeeSchedule fees(CurrencyCode source) {
        return FEES.get(source);
    }

    static Limits limits(CurrencyCode source) {
        return LIMITS.get(source);
    }
}
