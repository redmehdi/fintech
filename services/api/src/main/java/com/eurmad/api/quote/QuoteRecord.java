package com.eurmad.api.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A persisted, immutable quote as read from {@code fx_quotes}. Money is integer minor units; the
 * rate is target per one source at scale 12. {@code feeFixedMinor} is the fixed component of
 * {@code feeSourceMinor} (fee breakdown, PRD §7); the remainder is the rounded variable component.
 *
 * <p>{@code status} is the stored status. Expiry is decided server-side against the server clock
 * via {@link #effectiveStatus(Instant)}: an {@code ISSUED} quote is {@code EXPIRED} from {@code
 * expiresAt} (inclusive) onwards, whether or not the stored status has been updated.
 */
public record QuoteRecord(
        UUID id,
        UUID customerId,
        CurrencyCode sourceCurrency,
        CurrencyCode targetCurrency,
        long sourceAmountMinor,
        long feeSourceMinor,
        long feeFixedMinor,
        long totalDebitMinor,
        long targetAmountMinor,
        BigDecimal rate,
        String rateSource,
        Instant createdAt,
        Instant expiresAt,
        String status) {

    public static final String ISSUED = "ISSUED";
    public static final String CONSUMED = "CONSUMED";
    public static final String EXPIRED = "EXPIRED";

    public String effectiveStatus(Instant now) {
        if (ISSUED.equals(status) && !now.isBefore(expiresAt)) {
            return EXPIRED;
        }
        return status;
    }
}
