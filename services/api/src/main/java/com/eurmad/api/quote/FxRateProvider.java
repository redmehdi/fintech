package com.eurmad.api.quote;

import java.math.BigDecimal;

/**
 * Port for FX rate sources. In the MVP the only implementation is the deterministic {@link
 * SimulatedFxRateProvider}; a partner rate source requires the legal/partner milestone (FX-019,
 * FX-020) and a reviewed migration, and is rejected at runtime until then.
 */
public interface FxRateProvider {

    /** Stable provider identifier. */
    String name();

    /** Rate source label persisted on the quote. */
    String rateSource();

    /** Canonical rate in MAD per one EUR for a quote in the given direction. */
    BigDecimal madPerEur(CurrencyCode source, CurrencyCode target);
}
