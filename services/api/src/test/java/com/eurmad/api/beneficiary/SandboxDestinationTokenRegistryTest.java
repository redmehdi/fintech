package com.eurmad.api.beneficiary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** FX-006 sandbox destination-token registry (synthetic fixtures only). */
class SandboxDestinationTokenRegistryTest {

    private final SandboxDestinationTokenRegistry registry = new SandboxDestinationTokenRegistry();

    @ParameterizedTest
    @CsvSource({
        "sim-token:es-bank-esb1, ES, BANK_ACCOUNT",
        "sim-token:es-bank-esb3, ES, BANK_ACCOUNT",
        "sim-token:es-cash-esc2, ES, PARTNER_CASH_PICKUP",
        "sim-token:ma-bank-mab2, MA, BANK_ACCOUNT",
        "sim-token:ma-cash-mac1, MA, PARTNER_CASH_PICKUP"})
    void acceptsIssuedTokenForItsCountryAndMethod(String token, String country, String method) {
        assertThat(registry.isIssued(token, country, method)).isTrue();
        // Every fixture is also a well-formed sandbox token that can be masked and stored.
        assertThat(BeneficiaryRules.isSandboxToken(token)).isTrue();
    }

    @Test
    void rejectsIssuedTokenForAnotherCountryOrMethod() {
        assertThat(registry.isIssued("sim-token:ma-bank-mab1", "ES", "BANK_ACCOUNT")).isFalse();
        assertThat(registry.isIssued("sim-token:ma-bank-mab1", "MA", "PARTNER_CASH_PICKUP")).isFalse();
        assertThat(registry.isIssued("sim-token:es-cash-esc1", "ES", "BANK_ACCOUNT")).isFalse();
        assertThat(registry.isIssued("sim-token:es-bank-esb1", null, null)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "sim-token:fabricated-ref-z9y8",          // well-formed but never issued
        "sim-token:ES91-2100-0418-4502-0005-1332", // formatted account number (synthetic)
        "sim-token:ES91.2100.0418.4502",
        "sim-token:es-bank-esb9",                 // registry-shaped but never issued
        "SIM-TOKEN:es-bank-esb1",
        "sim-token:es-bank-esb1 ",
        "sim-token:quarantined"})                 // inert placeholder written by V4 migration
    void rejectsTokensNotInTheRegistry(String token) {
        assertThat(registry.isIssued(token, "ES", "BANK_ACCOUNT")).isFalse();
    }
}
