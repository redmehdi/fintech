package com.eurmad.api.beneficiary;

import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Deterministic SIMULATION-mode issuer: a fixed registry of synthetic destination tokens, each bound
 * to one country and payout method. Tokens resolve to no real account or pickup location. A token
 * that only looks like a sandbox token (a fabricated reference or a formatted account number) is
 * not in the registry and is rejected. The fixtures are listed in {@code contracts/openapi.yaml}
 * ({@code BeneficiaryCreate.destinationTokenRef}).
 */
@Component
public class SandboxDestinationTokenRegistry implements DestinationTokenRegistry {

    private record Binding(String country, String payoutMethod) {}

    private static final Binding ES_BANK = new Binding("ES", "BANK_ACCOUNT");
    private static final Binding ES_CASH = new Binding("ES", "PARTNER_CASH_PICKUP");
    private static final Binding MA_BANK = new Binding("MA", "BANK_ACCOUNT");
    private static final Binding MA_CASH = new Binding("MA", "PARTNER_CASH_PICKUP");

    private static final Map<String, Binding> FIXTURES = Map.of(
            "sim-token:es-bank-esb1", ES_BANK,
            "sim-token:es-bank-esb2", ES_BANK,
            "sim-token:es-bank-esb3", ES_BANK,
            "sim-token:es-cash-esc1", ES_CASH,
            "sim-token:es-cash-esc2", ES_CASH,
            "sim-token:ma-bank-mab1", MA_BANK,
            "sim-token:ma-bank-mab2", MA_BANK,
            "sim-token:ma-bank-mab3", MA_BANK,
            "sim-token:ma-cash-mac1", MA_CASH,
            "sim-token:ma-cash-mac2", MA_CASH);

    @Override
    public boolean isIssued(String token, String country, String payoutMethod) {
        if (token == null) {
            return false;
        }
        Binding binding = FIXTURES.get(token);
        return binding != null
                && Objects.equals(binding.country(), country)
                && Objects.equals(binding.payoutMethod(), payoutMethod);
    }
}
