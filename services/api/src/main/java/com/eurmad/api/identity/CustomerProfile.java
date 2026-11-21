package com.eurmad.api.identity;

import java.util.UUID;

/**
 * Contract {@code Customer} representation. Deliberately excludes the OIDC subject and email.
 */
public record CustomerProfile(UUID id, String residencyCountry, String kycStatus, String status) {}
