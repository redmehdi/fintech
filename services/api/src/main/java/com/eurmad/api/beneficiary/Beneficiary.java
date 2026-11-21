package com.eurmad.api.beneficiary;

import java.util.UUID;

/**
 * Contract {@code Beneficiary} representation. Deliberately excludes the destination token, the
 * owner ID and timestamps: only the masked destination is ever exposed.
 */
public record Beneficiary(UUID id, String displayName, String country, String payoutMethod,
        String maskedDestination) {}
