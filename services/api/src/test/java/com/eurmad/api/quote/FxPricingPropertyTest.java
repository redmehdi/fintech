package com.eurmad.api.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * FX-007 BigDecimal property tests. Inputs are drawn from a fixed-seed generator so failures are
 * reproducible; each property is checked against an independent exact BigDecimal oracle.
 */
class FxPricingPropertyTest {

    private static final long SEED = 0x5EED_F007L;
    private static final int ITERATIONS = 20_000;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    /** Positive rate with up to 12 fractional digits in (0, 1000]. */
    private static BigDecimal randomRate(SplittableRandom random) {
        return BigDecimal.valueOf(random.nextLong(1, 1_000_000_000_000_001L), RateConvention.RATE_SCALE);
    }

    @Test
    void convertedAmountIsTheHalfEvenRoundingOfTheExactProduct() {
        SplittableRandom random = new SplittableRandom(SEED);
        for (int i = 0; i < ITERATIONS; i++) {
            long source = random.nextLong(1, 1_000_000_000_000L);
            BigDecimal rate = randomRate(random);
            long target = FxPricing.convert(CurrencyCode.EUR, CurrencyCode.MAD, source, rate);

            BigDecimal exact = BigDecimal.valueOf(source).multiply(rate);
            BigDecimal error = BigDecimal.valueOf(target).subtract(exact).abs();
            assertThat(error).as("source=%d rate=%s", source, rate).isLessThanOrEqualTo(HALF);
            if (error.compareTo(HALF) == 0) {
                assertThat(target % 2).as("tie must round to even: source=%d rate=%s", source, rate).isZero();
            }
            assertThat(target).isEqualTo(exact.setScale(0, RoundingMode.HALF_EVEN).longValueExact());
        }
    }

    @Test
    void breakdownIsConsistentAndMonotonicInTheSourceAmount() {
        SplittableRandom random = new SplittableRandom(SEED + 1);
        for (int i = 0; i < ITERATIONS; i++) {
            CurrencyCode source = random.nextBoolean() ? CurrencyCode.EUR : CurrencyCode.MAD;
            CurrencyCode target = source == CurrencyCode.EUR ? CurrencyCode.MAD : CurrencyCode.EUR;
            FeeSchedule fees = new FeeSchedule(random.nextLong(0, 100_000), random.nextInt(0, 10_001));
            BigDecimal rate = randomRate(random);
            long amount = random.nextLong(1, 10_000_000_000L);

            FxPricing.Breakdown price = FxPricing.price(source, target, amount, rate, fees);
            FxPricing.Breakdown next = FxPricing.price(source, target, amount + 1, rate, fees);

            assertThat(price.totalDebitMinor()).isEqualTo(price.sourceAmountMinor() + price.feeSourceMinor());
            assertThat(price.feeSourceMinor()).isGreaterThanOrEqualTo(fees.fixedMinor());
            // The recorded fixed component is the tariff's, unrounded.
            assertThat(price.feeFixedMinor()).isEqualTo(fees.fixedMinor());
            BigDecimal exactVariable = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(fees.basisPoints()))
                    .movePointLeft(4);
            assertThat(BigDecimal.valueOf(price.feeSourceMinor() - fees.fixedMinor()).subtract(exactVariable).abs())
                    .isLessThanOrEqualTo(HALF);
            assertThat(price.targetAmountMinor()).isGreaterThanOrEqualTo(0);
            assertThat(next.targetAmountMinor()).isGreaterThanOrEqualTo(price.targetAmountMinor());
            assertThat(next.feeSourceMinor()).isGreaterThanOrEqualTo(price.feeSourceMinor());
            assertThat(next.totalDebitMinor()).isGreaterThan(price.totalDebitMinor());
            // Deterministic: identical inputs give identical output.
            assertThat(FxPricing.price(source, target, amount, rate, fees)).isEqualTo(price);
        }
    }

    @Test
    void inverseRateNeverOverstatesAndIsWithinOneUnitOfTheLastPlace() {
        SplittableRandom random = new SplittableRandom(SEED + 2);
        BigDecimal ulp = BigDecimal.ONE.movePointLeft(RateConvention.RATE_SCALE);
        for (int i = 0; i < ITERATIONS; i++) {
            // Canonical MAD per EUR in [1, 100) with up to 12 fractional digits.
            BigDecimal madPerEur = BigDecimal.valueOf(random.nextLong(1_000_000_000_000L, 100_000_000_000_000L),
                    RateConvention.RATE_SCALE);
            BigDecimal eurPerMad = RateConvention.targetPerSource(CurrencyCode.MAD, CurrencyCode.EUR, madPerEur);

            assertThat(eurPerMad.scale()).isEqualTo(RateConvention.RATE_SCALE);
            BigDecimal product = eurPerMad.multiply(madPerEur);
            // r * R <= 1: the quoted MAD->EUR rate never buys more than the canonical rate allows.
            assertThat(product).as("R=%s", madPerEur).isLessThanOrEqualTo(BigDecimal.ONE);
            // r is the largest 12-digit value with that property.
            assertThat(eurPerMad.add(ulp).multiply(madPerEur)).as("R=%s", madPerEur).isGreaterThan(BigDecimal.ONE);

            assertThat(RateConvention.targetPerSource(CurrencyCode.EUR, CurrencyCode.MAD, madPerEur))
                    .isEqualByComparingTo(madPerEur);
        }
    }

    @Test
    void simulatedPolicyPricesEveryAmountInRangeWithAPositiveTarget() {
        SplittableRandom random = new SplittableRandom(SEED + 3);
        SimulatedFxRateProvider provider = new SimulatedFxRateProvider();
        for (CurrencyCode source : CurrencyCode.values()) {
            CurrencyCode target = source == CurrencyCode.EUR ? CurrencyCode.MAD : CurrencyCode.EUR;
            BigDecimal rate = RateConvention.targetPerSource(source, target, provider.madPerEur(source, target));
            QuotePolicy.Limits limits = QuotePolicy.limits(source);
            for (int i = 0; i < ITERATIONS / 4; i++) {
                long amount = i == 0 ? limits.minMinor()
                        : i == 1 ? limits.maxMinor()
                        : random.nextLong(limits.minMinor(), limits.maxMinor() + 1);
                FxPricing.Breakdown price = FxPricing.price(source, target, amount, rate, QuotePolicy.fees(source));
                assertThat(price.targetAmountMinor()).as("%s %d", source, amount).isPositive();
                assertThat(price.feeSourceMinor()).isPositive();
            }
        }
    }
}
