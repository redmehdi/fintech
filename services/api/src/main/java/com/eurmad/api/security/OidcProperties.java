package com.eurmad.api.security;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Trusted OIDC issuer configuration bound from {@code fintech.security.oidc.*}.
 *
 * <p>Fails closed at startup: every value is mandatory, the JWKS endpoint must use https unless it
 * is a loopback address (local development and tests), and the clock skew is bounded.
 *
 * @param issuer exact expected {@code iss} claim
 * @param jwkSetUri JWKS endpoint of the issuer used to verify token signatures
 * @param audience value that must be present in the {@code aud} claim
 * @param clockSkew tolerance applied to {@code exp}/{@code nbf}
 */
@ConfigurationProperties(prefix = "fintech.security.oidc")
public record OidcProperties(
        String issuer, String jwkSetUri, String audience, @DefaultValue("30s") Duration clockSkew) {

    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(2);
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    public OidcProperties {
        requireText(issuer, "issuer");
        requireText(audience, "audience");
        requireText(jwkSetUri, "jwk-set-uri");
        requireSecureUri(jwkSetUri, "jwk-set-uri");
        if (clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(MAX_CLOCK_SKEW) > 0) {
            throw new IllegalStateException(
                    "fintech.security.oidc.clock-skew must be between 0 and " + MAX_CLOCK_SKEW);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("fintech.security.oidc." + name + " is required");
        }
    }

    private static void requireSecureUri(String value, String name) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("fintech.security.oidc." + name + " is not a valid URI", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https") && !host.isEmpty();
        boolean loopbackHttp = scheme.equals("http") && LOOPBACK_HOSTS.contains(host);
        if (!https && !loopbackHttp) {
            throw new IllegalStateException(
                    "fintech.security.oidc." + name + " must use https (http allowed for loopback only)");
        }
    }
}
