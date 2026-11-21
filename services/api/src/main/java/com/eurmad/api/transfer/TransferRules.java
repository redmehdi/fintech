package com.eurmad.api.transfer;

import com.eurmad.api.error.ApiProblemException;
import com.eurmad.api.quote.CurrencyCode;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure validation for transfer requests (FX-008). Fails closed: anything not explicitly allowed is
 * rejected with {@code 400}; business rules ({@code 422}) are checked later by the service.
 */
final class TransferRules {

    static final Set<String> PURPOSES = Set.of("FAMILY_SUPPORT", "PERSONAL_TRANSFER", "EDUCATION", "OTHER_REVIEW");

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[\\x21-\\x7E]{8,128}$");

    private TransferRules() {}

    /** A transfer request after validation; IDs are parsed so their textual case is irrelevant. */
    record ValidCreate(UUID quoteId, UUID beneficiaryId, String purpose) {}

    static ValidCreate validateCreate(String quoteId, String beneficiaryId, String purpose) {
        UUID quote = parseUuid(quoteId);
        UUID beneficiary = parseUuid(beneficiaryId);
        if (purpose == null || !PURPOSES.contains(purpose)) {
            throw ApiProblemException.badRequest();
        }
        return new ValidCreate(quote, beneficiary, purpose);
    }

    private static UUID parseUuid(String value) {
        // UUID.fromString alone accepts non-canonical forms such as "1-1-1-1-1".
        if (value == null || !UUID_TEXT.matcher(value).matches()) {
            throw ApiProblemException.badRequest();
        }
        return UUID.fromString(value.toLowerCase(Locale.ROOT));
    }

    /** Printable US-ASCII without spaces, 8–128 characters (same rule as quotes and beneficiaries). */
    static String requireIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiProblemException.badRequest();
        }
        return key;
    }

    /**
     * Payout country a beneficiary must be in to receive the quote's target currency: MAD is paid
     * out in Morocco and EUR in Spain. Empty for any other currency (fail closed).
     */
    static Optional<String> payoutCountry(CurrencyCode target) {
        return switch (target) {
            case MAD -> Optional.of("MA");
            case EUR -> Optional.of("ES");
        };
    }
}
