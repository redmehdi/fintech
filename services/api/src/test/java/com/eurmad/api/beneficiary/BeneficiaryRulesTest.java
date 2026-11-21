package com.eurmad.api.beneficiary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eurmad.api.error.ApiProblemException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/** FX-006 validation and masking rules (synthetic values only). */
class BeneficiaryRulesTest {

    private static final String TOKEN = "sim-token:fx-006:b001";

    private static HttpStatus statusOf(Runnable call) {
        try {
            call.run();
            return HttpStatus.OK;
        } catch (ApiProblemException e) {
            return e.status();
        }
    }

    @Test
    void acceptsEveryCountryAndPayoutMethodCombination() {
        for (String country : BeneficiaryRules.COUNTRIES) {
            for (String method : BeneficiaryRules.PAYOUT_METHODS) {
                BeneficiaryRules.ValidCreate valid =
                        BeneficiaryRules.validateCreate("  Synthetic Name  ", country, method, TOKEN);
                assertThat(valid.displayName()).isEqualTo("Synthetic Name");
                assertThat(valid.country()).isEqualTo(country);
                assertThat(valid.payoutMethod()).isEqualTo(method);
            }
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"FR", "es", "MAR", " ES", "ESP"})
    void rejectsUnknownCountries(String country) {
        assertThat(statusOf(() -> BeneficiaryRules.validateCreate("Name", country, "BANK_ACCOUNT", TOKEN)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"CASH", "bank_account", "WALLET", "CARD", "CRYPTO", "P2P_DIRECT"})
    void rejectsUnknownPayoutMethods(String method) {
        assertThat(statusOf(() -> BeneficiaryRules.validateCreate("Name", "MA", method, TOKEN)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "Line\nBreak", "Tab\tName", "Zero​width", "Bidi‮override"})
    void rejectsBlankOrControlCharacterNames(String name) {
        assertThat(statusOf(() -> BeneficiaryRules.validateCreate(name, "MA", "BANK_ACCOUNT", TOKEN)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void enforcesDisplayNameLengthAfterTrimming() {
        assertThat(BeneficiaryRules.normalizeDisplayName("x".repeat(120))).hasSize(120);
        assertThat(BeneficiaryRules.normalizeDisplayName(" " + "x".repeat(120) + " ")).hasSize(120);
        assertThat(statusOf(() -> BeneficiaryRules.normalizeDisplayName("x".repeat(121))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(BeneficiaryRules.normalizeDisplayName("Synthétique بن")).isEqualTo("Synthétique بن");
    }

    @Test
    void displayNameLengthCountsCodePoints() {
        String supplementary = "𝐀"; // U+1D400, one code point, two UTF-16 units
        assertThat(BeneficiaryRules.normalizeDisplayName(supplementary.repeat(120))).hasSize(240);
        assertThat(statusOf(() -> BeneficiaryRules.normalizeDisplayName(supplementary.repeat(121))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> BeneficiaryRules.normalizeDisplayName("\uD835"))) // lone surrogate
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingTokenIsBadRequest() {
        assertThat(statusOf(() -> BeneficiaryRules.validateCreate("Name", "MA", "BANK_ACCOUNT", null)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> BeneficiaryRules.validateCreate("Name", "MA", "BANK_ACCOUNT", "")))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ES9121000418450200051332",           // raw IBAN-shaped value (synthetic)
        "sim-token:ES9121000418450200051332", // raw account wrapped in a sandbox prefix
        "sim-token:123456789",                // long digit run
        "psp-live:tok_abcdef",                // non-sandbox provider token
        "SIM-TOKEN:abcd",
        "sim-token:abc",                      // too short
        "sim-token:has space",
        "sim-token:a/b/c/d",
        " sim-token:abcd"})
    void rejectsNonSandboxOrRawLookingTokensAsBusinessRuleFailure(String token) {
        assertThat(statusOf(() -> BeneficiaryRules.validateCreate("Name", "MA", "BANK_ACCOUNT", token)))
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void tokenLengthIsBounded() {
        assertThat(BeneficiaryRules.isSandboxToken("sim-token:" + "a".repeat(100))).isTrue();
        assertThat(BeneficiaryRules.isSandboxToken("sim-token:" + "a".repeat(101))).isFalse();
        assertThat(BeneficiaryRules.isSandboxToken("sim-token:12345678x")).isTrue();
    }

    @Test
    void maskShowsOnlyTheLastFourTokenCharacters() {
        assertThat(BeneficiaryRules.mask(TOKEN)).isEqualTo("****b001");
        assertThat(BeneficiaryRules.mask("sim-token:abcd")).isEqualTo("****abcd");
        assertThatThrownBy(() -> BeneficiaryRules.mask("ES9121000418450200051332"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "has space-in-key", "tab\tin-key-x"})
    void rejectsMalformedIdempotencyKeys(String key) {
        assertThat(statusOf(() -> BeneficiaryRules.requireIdempotencyKey(key))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void idempotencyKeyLengthBounds() {
        assertThat(BeneficiaryRules.requireIdempotencyKey("k".repeat(8))).hasSize(8);
        assertThat(BeneficiaryRules.requireIdempotencyKey("k".repeat(128))).hasSize(128);
        assertThat(statusOf(() -> BeneficiaryRules.requireIdempotencyKey("k".repeat(129))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
