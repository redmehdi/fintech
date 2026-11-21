package com.eurmad.api.quote;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Contract {@code Quote} representation. Money values are decimal strings of integer minor units
 * and the rate is a decimal string, so no client parses them as binary floating point. Excludes the
 * owner ID.
 */
public record Quote(
        UUID id,
        String sourceCurrency,
        String targetCurrency,
        String sourceAmountMinor,
        String targetAmountMinor,
        String feeSourceMinor,
        String totalDebitMinor,
        String rate,
        String rateSource,
        String expiresAt,
        String mode,
        String status) {

    static final String MODE = "SIMULATION";

    static Quote from(QuoteRecord quote, Instant now) {
        return new Quote(
                quote.id(),
                quote.sourceCurrency().name(),
                quote.targetCurrency().name(),
                Long.toString(quote.sourceAmountMinor()),
                Long.toString(quote.targetAmountMinor()),
                Long.toString(quote.feeSourceMinor()),
                Long.toString(quote.totalDebitMinor()),
                quote.rate().setScale(RateConvention.RATE_SCALE).toPlainString(),
                quote.rateSource(),
                DateTimeFormatter.ISO_INSTANT.format(quote.expiresAt()),
                MODE,
                quote.effectiveStatus(now));
    }
}
