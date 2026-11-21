package com.eurmad.api.quote;

import com.eurmad.api.error.ApiProblemException;
import java.math.BigInteger;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validation for quote requests (FX-007). Fails closed: anything not explicitly allowed is
 * rejected. Schema violations yield 400; every schema check runs before any business-rule (422)
 * check, so a malformed request never reveals limit information.
 */
final class QuoteRules {

    static final Set<String> PURPOSES = Set.of("FAMILY_SUPPORT", "PERSONAL_TRANSFER", "EDUCATION", "OTHER_REVIEW");

    private static final Pattern MINOR_AMOUNT = Pattern.compile("^[0-9]+$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[\\x21-\\x7E]{8,128}$");
    /** More significant digits are certainly above every limit; avoids parsing unbounded input. */
    private static final int MAX_AMOUNT_DIGITS = 40;

    private QuoteRules() {}

    /** A quote request after validation; the amount is normalized (no leading zeros). */
    record ValidRequest(CurrencyCode source, CurrencyCode target, long sourceAmountMinor, String purpose) {}

    static ValidRequest validate(String sourceCurrency, String targetCurrency, String sourceAmountMinor,
            String purpose) {
        CurrencyCode source = CurrencyCode.parse(sourceCurrency).orElseThrow(ApiProblemException::badRequest);
        CurrencyCode target = CurrencyCode.parse(targetCurrency).orElseThrow(ApiProblemException::badRequest);
        if (source == target) {
            throw ApiProblemException.badRequest();
        }
        if (purpose == null || !PURPOSES.contains(purpose)) {
            throw ApiProblemException.badRequest();
        }
        if (sourceAmountMinor == null || !MINOR_AMOUNT.matcher(sourceAmountMinor).matches()) {
            throw ApiProblemException.badRequest();
        }
        // Leading zeros are not significant: the digit bound applies to the normalized value, so a
        // zero-padded in-range amount is accepted and a long run of zeros is still a zero (400).
        String significant = stripLeadingZeros(sourceAmountMinor);
        if (significant.isEmpty()) {
            throw ApiProblemException.badRequest();
        }
        boolean tooLong = significant.length() > MAX_AMOUNT_DIGITS;
        BigInteger amount = tooLong ? null : new BigInteger(significant);
        // Business rule: simulated per-currency limits.
        QuotePolicy.Limits limits = QuotePolicy.limits(source);
        if (amount == null
                || amount.compareTo(BigInteger.valueOf(limits.minMinor())) < 0
                || amount.compareTo(BigInteger.valueOf(limits.maxMinor())) > 0) {
            throw ApiProblemException.unprocessable();
        }
        return new ValidRequest(source, target, amount.longValueExact(), purpose);
    }

    private static String stripLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }

    /**
     * Printable US-ASCII without spaces, 8–128 characters, as published in the contract's {@code
     * Idempotency-Key} pattern (same rule as the beneficiary API).
     */
    static String requireIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiProblemException.badRequest();
        }
        return key;
    }
}
