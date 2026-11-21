package com.eurmad.api.beneficiary;

import com.eurmad.api.error.ApiProblemException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validation and masking rules for beneficiaries (FX-006). Fails closed: anything not
 * explicitly allowed is rejected.
 *
 * <ul>
 *   <li>Schema violations (missing/blank/oversized fields, unknown country or payout method,
 *       malformed idempotency key) yield 400.
 *   <li>A destination token that is not a sandbox token yields 422: only the sandbox may issue
 *       destination tokens in SIMULATION mode.
 * </ul>
 */
final class BeneficiaryRules {

    static final Set<String> COUNTRIES = Set.of("ES", "MA");
    static final Set<String> PAYOUT_METHODS = Set.of("BANK_ACCOUNT", "PARTNER_CASH_PICKUP");

    static final int DISPLAY_NAME_MAX = 120;
    static final String SANDBOX_TOKEN_PREFIX = "sim-token:";

    private static final Pattern SANDBOX_TOKEN = Pattern.compile("^sim-token:[A-Za-z0-9._:-]{4,100}$");
    /** A long digit run may be a raw account/card number wrapped in a token; never accept it. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{9,}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[\\x21-\\x7E]{8,128}$");
    private static final int MASK_VISIBLE = 4;

    private BeneficiaryRules() {}

    /** A create request after validation and normalization. */
    record ValidCreate(String displayName, String country, String payoutMethod, String destinationTokenRef) {}

    static ValidCreate validateCreate(String displayName, String country, String payoutMethod,
            String destinationTokenRef) {
        String name = normalizeDisplayName(displayName);
        if (country == null || !COUNTRIES.contains(country)) {
            throw ApiProblemException.badRequest();
        }
        if (payoutMethod == null || !PAYOUT_METHODS.contains(payoutMethod)) {
            throw ApiProblemException.badRequest();
        }
        if (destinationTokenRef == null || destinationTokenRef.isEmpty()) {
            throw ApiProblemException.badRequest();
        }
        if (!isSandboxToken(destinationTokenRef)) {
            throw ApiProblemException.unprocessable();
        }
        return new ValidCreate(name, country, payoutMethod, destinationTokenRef);
    }

    static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            throw ApiProblemException.badRequest();
        }
        String name = displayName.strip();
        // Length in Unicode code points (as the contract and PostgreSQL char_length count), not
        // UTF-16 code units: a supplementary character counts once.
        if (name.isEmpty() || name.codePointCount(0, name.length()) > DISPLAY_NAME_MAX
                || name.codePoints().anyMatch(BeneficiaryRules::isDisallowedCharacter)) {
            throw ApiProblemException.badRequest();
        }
        return name;
    }

    private static boolean isDisallowedCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.UNASSIGNED
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE;
    }

    static boolean isSandboxToken(String token) {
        return token != null
                && SANDBOX_TOKEN.matcher(token).matches()
                && !LONG_DIGIT_RUN.matcher(token).find();
    }

    /** Four mask characters plus the token's last four characters; the token itself is opaque. */
    static String mask(String destinationTokenRef) {
        if (!isSandboxToken(destinationTokenRef)) {
            throw new IllegalArgumentException("Only validated sandbox tokens can be masked");
        }
        return "****" + destinationTokenRef.substring(destinationTokenRef.length() - MASK_VISIBLE);
    }

    static String requireIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiProblemException.badRequest();
        }
        return key;
    }
}
