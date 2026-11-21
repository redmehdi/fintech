package com.eurmad.api.quote;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Deterministic sandbox rate source. Rates are fixed and <b>illustrative only</b>; they are not
 * market rates and must never be presented as such. A spread between the two directions mirrors
 * how a provider would price buying and selling EUR.
 */
@Component
class SimulatedFxRateProvider implements FxRateProvider {

    static final String NAME = "SIMULATOR";
    static final String RATE_SOURCE = "SIMULATED_ILLUSTRATIVE";

    /** MAD received per EUR sold (EUR→MAD). */
    static final BigDecimal EUR_TO_MAD_MAD_PER_EUR = new BigDecimal("10.70");
    /** MAD paid per EUR bought (MAD→EUR). */
    static final BigDecimal MAD_TO_EUR_MAD_PER_EUR = new BigDecimal("10.90");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String rateSource() {
        return RATE_SOURCE;
    }

    @Override
    public BigDecimal madPerEur(CurrencyCode source, CurrencyCode target) {
        if (source == CurrencyCode.EUR && target == CurrencyCode.MAD) {
            return EUR_TO_MAD_MAD_PER_EUR;
        }
        if (source == CurrencyCode.MAD && target == CurrencyCode.EUR) {
            return MAD_TO_EUR_MAD_PER_EUR;
        }
        throw new IllegalArgumentException("Unsupported currency pair");
    }
}
