package com.eurmad.api.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eurmad.api.error.ApiProblemException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/** FX-007 quote request validation: schema failures are 400, limit failures 422. */
class QuoteRulesTest {

    private static HttpStatus statusOf(Runnable call) {
        try {
            call.run();
        } catch (ApiProblemException e) {
            return e.status();
        }
        throw new AssertionError("expected ApiProblemException");
    }

    @Test
    void acceptsValidRequestAndNormalizesLeadingZeros() {
        QuoteRules.ValidRequest request = QuoteRules.validate("EUR", "MAD", "000010000", "FAMILY_SUPPORT");
        assertThat(request).isEqualTo(new QuoteRules.ValidRequest(CurrencyCode.EUR, CurrencyCode.MAD, 10_000,
                "FAMILY_SUPPORT"));
        assertThat(QuoteRules.validate("MAD", "EUR", "1000", "OTHER_REVIEW").source()).isEqualTo(CurrencyCode.MAD);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "EUR, EUR, 10000, FAMILY_SUPPORT",
        "MAD, MAD, 10000, FAMILY_SUPPORT",
        "eur, MAD, 10000, FAMILY_SUPPORT",
        "USD, MAD, 10000, FAMILY_SUPPORT",
        "NULL, MAD, 10000, FAMILY_SUPPORT",
        "EUR, NULL, 10000, FAMILY_SUPPORT",
        "EUR, MAD, 10000, NULL",
        "EUR, MAD, 10000, GIFT",
        "EUR, MAD, NULL, FAMILY_SUPPORT",
        "EUR, MAD, 0, FAMILY_SUPPORT",
        "EUR, MAD, 000, FAMILY_SUPPORT",
        "EUR, MAD, -100, FAMILY_SUPPORT",
        "EUR, MAD, 100.00, FAMILY_SUPPORT",
        "EUR, MAD, 1e5, FAMILY_SUPPORT",
        "EUR, MAD, ' 100', FAMILY_SUPPORT",
        "EUR, MAD, '', FAMILY_SUPPORT",
        "EUR, MAD, '１００', FAMILY_SUPPORT",
    })
    void schemaViolationsAreBadRequest(String source, String target, String amount, String purpose) {
        assertThat(statusOf(() -> QuoteRules.validate(source, target, amount, purpose)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({
        "EUR, MAD, 99",
        "EUR, MAD, 1000001",
        "MAD, EUR, 999",
        "MAD, EUR, 10000001",
        "EUR, MAD, 99999999999999999999",
        "EUR, MAD, 9223372036854775808",
    })
    void amountsOutsideSimulatedLimitsAreUnprocessable(String source, String target, String amount) {
        assertThat(statusOf(() -> QuoteRules.validate(source, target, amount, "FAMILY_SUPPORT")))
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void unboundedDigitStringsAreRejectedWithoutParsing() {
        assertThat(statusOf(() -> QuoteRules.validate("EUR", "MAD", "9".repeat(10_000), "FAMILY_SUPPORT")))
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void digitBoundAppliesToSignificantDigitsOnly() {
        // Zero-padded in-range amount (schema-valid) is accepted and normalized.
        assertThat(QuoteRules.validate("EUR", "MAD", "0".repeat(100) + "10000", "FAMILY_SUPPORT")
                .sourceAmountMinor()).isEqualTo(10_000);
        // Any run of zeros is a zero amount: schema violation, never a limit (422) answer.
        assertThat(statusOf(() -> QuoteRules.validate("EUR", "MAD", "0".repeat(10_000), "FAMILY_SUPPORT")))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> QuoteRules.validate("EUR", "MAD", "0".repeat(100) + "9".repeat(41),
                "FAMILY_SUPPORT"))).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdefgh", "!~#$%&'*+-.^_`|0123", "idem-0f8fad5b-d9cb-469f-a165-70867728950e"})
    void wellFormedIdempotencyKeysAreAccepted(String key) {
        assertThat(QuoteRules.requireIdempotencyKey(key)).isEqualTo(key);
        assertThat(QuoteRules.requireIdempotencyKey("k".repeat(128))).hasSize(128);
    }

    @Test
    void limitBoundariesAreInclusive() {
        assertThat(QuoteRules.validate("EUR", "MAD", "100", "EDUCATION").sourceAmountMinor()).isEqualTo(100);
        assertThat(QuoteRules.validate("EUR", "MAD", "1000000", "EDUCATION").sourceAmountMinor())
                .isEqualTo(1_000_000);
        assertThat(QuoteRules.validate("MAD", "EUR", "1000", "EDUCATION").sourceAmountMinor()).isEqualTo(1_000);
        assertThat(QuoteRules.validate("MAD", "EUR", "10000000", "EDUCATION").sourceAmountMinor())
                .isEqualTo(10_000_000);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "has space-in-key", "éééééééé", "tab\tin-key"})
    void malformedIdempotencyKeysAreBadRequest(String key) {
        assertThatThrownBy(() -> QuoteRules.requireIdempotencyKey(key))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(statusOf(() -> QuoteRules.requireIdempotencyKey("k".repeat(129))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
