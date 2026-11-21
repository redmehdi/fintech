package com.eurmad.api.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * FX-007 golden rounding tests: hand-computed expected values for the simulated policy, HALF_EVEN
 * ties in both the converted amount and the fee, and the rate convention for each direction.
 */
class FxPricingGoldenTest {

    private static final BigDecimal EUR_MAD = RateConvention.targetPerSource(
            CurrencyCode.EUR, CurrencyCode.MAD, SimulatedFxRateProvider.EUR_TO_MAD_MAD_PER_EUR);
    private static final BigDecimal MAD_EUR = RateConvention.targetPerSource(
            CurrencyCode.MAD, CurrencyCode.EUR, SimulatedFxRateProvider.MAD_TO_EUR_MAD_PER_EUR);

    @Test
    void rateConventionIsTargetPerSourceAtScaleTwelve() {
        assertThat(EUR_MAD.toPlainString()).isEqualTo("10.700000000000");
        // 1 / 10.90 = 0.091743119266055..., truncated (DOWN) so the rate never overstates the payout.
        assertThat(MAD_EUR.toPlainString()).isEqualTo("0.091743119266");
    }

    @ParameterizedTest(name = "EUR {0} -> MAD {1}, fee {2}, debit {3}")
    @CsvSource({
        // source, target, fee, totalDebit   (rate 10.70, fee 199 + 50 bps)
        "10000,   107000,   249,  10249",   // 100.00 EUR
        "100,     1070,     199,  299",     // minimum 1.00 EUR; variable fee 0.5 -> 0 (tie, to even)
        "105,     1124,     200,  305",     // 1123.5 -> 1124 (tie, to even); fee 0.525 -> 1
        "115,     1230,     200,  315",     // 1230.5 -> 1230 (tie, to even); fee 0.575 -> 1
        "125,     1338,     200,  325",     // 1337.5 -> 1338 (tie, to even)
        "1000000, 10700000, 5199, 1005199", // maximum 10,000.00 EUR
    })
    void eurToMadGoldenValues(long source, long target, long fee, long debit) {
        FxPricing.Breakdown price = FxPricing.price(CurrencyCode.EUR, CurrencyCode.MAD, source, EUR_MAD,
                QuotePolicy.fees(CurrencyCode.EUR));
        assertThat(price.targetAmountMinor()).isEqualTo(target);
        assertThat(price.feeSourceMinor()).isEqualTo(fee);
        assertThat(price.totalDebitMinor()).isEqualTo(debit);
        assertThat(price.sourceAmountMinor()).isEqualTo(source);
        // Breakdown: fixed part is exactly the tariff's; only the variable part is rounded.
        assertThat(price.feeFixedMinor()).isEqualTo(199);
        assertThat(price.feeSourceMinor() - price.feeFixedMinor()).isEqualTo(fee - 199);
    }

    @ParameterizedTest(name = "MAD {0} -> EUR {1}, fee {2}, debit {3}")
    @CsvSource({
        // source, target, fee, totalDebit   (rate 0.091743119266, fee 2000 + 50 bps)
        "100000,   9174,   2500,  102500",    // 9174.3119266 -> 9174
        "1000,     92,     2005,  3005",      // minimum; 91.743119266 -> 92
        "10000000, 917431, 52000, 10052000",  // maximum; 917431.19266 -> 917431
    })
    void madToEurGoldenValues(long source, long target, long fee, long debit) {
        FxPricing.Breakdown price = FxPricing.price(CurrencyCode.MAD, CurrencyCode.EUR, source, MAD_EUR,
                QuotePolicy.fees(CurrencyCode.MAD));
        assertThat(price.targetAmountMinor()).isEqualTo(target);
        assertThat(price.feeSourceMinor()).isEqualTo(fee);
        assertThat(price.totalDebitMinor()).isEqualTo(debit);
    }

    @ParameterizedTest(name = "{0} x 0.5 -> {1}")
    @CsvSource({"101, 50", "103, 52", "105, 52", "107, 54", "1, 0", "3, 2"})
    void convertedAmountTiesRoundHalfEven(long source, long expected) {
        assertThat(FxPricing.convert(CurrencyCode.MAD, CurrencyCode.EUR, source, new BigDecimal("0.5")))
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} at 50 bps -> {1}")
    @CsvSource({"100, 0", "300, 2", "500, 2", "700, 4", "299, 1", "301, 2"})
    void variableFeeTiesRoundHalfEven(long source, long expected) {
        assertThat(new FeeSchedule(0, 50).feeMinor(source)).isEqualTo(expected);
    }

    @Test
    void invalidRatesAreRejectedNeverRounded() {
        FeeSchedule fees = new FeeSchedule(0, 0);
        assertThatThrownBy(() -> FxPricing.price(CurrencyCode.EUR, CurrencyCode.MAD, 100,
                new BigDecimal("10.7000000000001"), fees)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.price(CurrencyCode.EUR, CurrencyCode.MAD, 100, BigDecimal.ZERO, fees))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.price(CurrencyCode.EUR, CurrencyCode.MAD, 100, new BigDecimal("-1"),
                fees)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.price(CurrencyCode.EUR, CurrencyCode.MAD, 100,
                new BigDecimal("12345678901"), fees)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.price(CurrencyCode.EUR, CurrencyCode.EUR, 100, BigDecimal.ONE, fees))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.price(CurrencyCode.EUR, CurrencyCode.MAD, 0, BigDecimal.ONE, fees))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowFailsInsteadOfWrapping() {
        assertThatThrownBy(() -> FxPricing.price(CurrencyCode.EUR, CurrencyCode.MAD, Long.MAX_VALUE,
                new BigDecimal("2"), new FeeSchedule(0, 0))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> FxPricing.price(CurrencyCode.EUR, CurrencyCode.MAD, Long.MAX_VALUE,
                new BigDecimal("0.5"), new FeeSchedule(1, 0))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void feeScheduleRejectsInvalidParameters() {
        assertThatThrownBy(() -> new FeeSchedule(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeeSchedule(0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeeSchedule(0, 10_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quoteIsExpiredFromItsExpiryInstantInclusive() {
        Instant expires = Instant.parse("2026-11-21T10:10:00Z");
        QuoteRecord issued = record(expires, QuoteRecord.ISSUED);
        assertThat(issued.effectiveStatus(expires.minusNanos(1))).isEqualTo(QuoteRecord.ISSUED);
        assertThat(issued.effectiveStatus(expires)).isEqualTo(QuoteRecord.EXPIRED);
        assertThat(issued.effectiveStatus(expires.plusSeconds(3600))).isEqualTo(QuoteRecord.EXPIRED);
        // A consumed quote stays consumed after its expiry.
        assertThat(record(expires, QuoteRecord.CONSUMED).effectiveStatus(expires.plusSeconds(1)))
                .isEqualTo(QuoteRecord.CONSUMED);
    }

    @Test
    void contractRepresentationUsesDecimalStrings() {
        Instant expires = Instant.parse("2026-11-21T10:10:00.123456Z");
        Quote quote = Quote.from(record(expires, QuoteRecord.ISSUED), expires.minusSeconds(1));
        assertThat(quote.rate()).isEqualTo("10.700000000000").matches("^[0-9]+(\\.[0-9]{1,12})?$");
        assertThat(quote.sourceAmountMinor()).isEqualTo("10000");
        assertThat(quote.targetAmountMinor()).isEqualTo("107000");
        assertThat(quote.feeSourceMinor()).isEqualTo("249");
        assertThat(quote.totalDebitMinor()).isEqualTo("10249");
        assertThat(quote.expiresAt()).isEqualTo("2026-11-21T10:10:00.123456Z");
        assertThat(quote.mode()).isEqualTo("SIMULATION");
    }

    private static QuoteRecord record(Instant expires, String status) {
        return new QuoteRecord(UUID.randomUUID(), UUID.randomUUID(), CurrencyCode.EUR, CurrencyCode.MAD, 10000, 249,
                199, 10249, 107000, EUR_MAD, SimulatedFxRateProvider.RATE_SOURCE, expires.minus(QuotePolicy.TTL),
                expires, status);
    }
}
