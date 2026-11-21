package com.eurmad.api.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Builds the strict JWT decoder for the configured OIDC issuer. */
public final class OidcJwtDecoders {

    static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration JWKS_READ_TIMEOUT = Duration.ofSeconds(5);
    static final int JWKS_SIZE_LIMIT_BYTES = 50 * 1024;
    /** How long a fetched JWKS is served before it is considered stale. */
    static final Duration JWKS_CACHE_TTL = Duration.ofMinutes(5);
    /** Max time a request waits for another thread's in-flight JWKS refresh. */
    static final Duration JWKS_REFRESH_WAIT = JWKS_CONNECT_TIMEOUT.plus(JWKS_READ_TIMEOUT);
    /** Minimum spacing between JWKS fetches, e.g. when tokens carry unknown key IDs. */
    static final Duration JWKS_MIN_REFRESH_INTERVAL = Duration.ofSeconds(30);

    /**
     * Accepted JOSE {@code typ} headers: RFC 9068 access tokens ({@code at+jwt}, with or without the
     * {@code application/} prefix), legacy {@code JWT}, and absent. Any other declared type (e.g.
     * {@code logout+jwt}, {@code secevent+jwt}) is rejected so other JWT kinds cannot be replayed
     * as access tokens.
     */
    static final Set<JOSEObjectType> ACCEPTED_TYPES = Set.of(
            new JOSEObjectType("at+jwt"),
            new JOSEObjectType("application/at+jwt"),
            JOSEObjectType.JWT);

    private OidcJwtDecoders() {}

    /**
     * Only asymmetric signatures from the issuer JWKS are accepted (RS256, ES256). Unsigned
     * ({@code alg=none}) and HMAC tokens are rejected by the decoder before claim validation.
     *
     * <p>JWKS fetching is abuse-bounded: keys are cached, concurrent refreshes are coalesced into a
     * single upstream request, and refreshes triggered by unknown key IDs are rate limited. Tokens
     * signed by known keys keep being verified from the cache while a refresh is throttled.
     */
    public static JwtDecoder create(OidcProperties properties) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(withAbsentType(ACCEPTED_TYPES)));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256), jwkSource(properties.jwkSetUri())));
        // Claims are validated by validator(...) below with the configured clock skew.
        processor.setJWTClaimsSetVerifier((claims, context) -> {});
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(properties));
        return decoder;
    }

    static JWKSource<SecurityContext> jwkSource(String jwkSetUri) {
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(
                (int) JWKS_CONNECT_TIMEOUT.toMillis(), (int) JWKS_READ_TIMEOUT.toMillis(), JWKS_SIZE_LIMIT_BYTES);
        return JWKSourceBuilder.<SecurityContext>create(toUrl(jwkSetUri), retriever)
                .cache(JWKS_CACHE_TTL.toMillis(), JWKS_REFRESH_WAIT.toMillis())
                .rateLimited(JWKS_MIN_REFRESH_INTERVAL.toMillis())
                .refreshAheadCache(false)
                .retrying(false)
                .outageTolerant(false)
                .build();
    }

    static OAuth2TokenValidator<Jwt> validator(OidcProperties properties) {
        String audience = properties.audience();
        return new DelegatingOAuth2TokenValidator<>(
                // exp is mandatory: JwtTimestampValidator only checks it when present.
                new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtTimestampValidator(properties.clockSkew()),
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<Object>(
                        JwtClaimNames.AUD, aud -> aud instanceof Collection<?> values && values.contains(audience)),
                new JwtClaimValidator<Object>(
                        JwtClaimNames.SUB, sub -> sub instanceof String value && !value.isBlank()));
    }

    private static JOSEObjectType[] withAbsentType(Set<JOSEObjectType> types) {
        JOSEObjectType[] allowed = types.toArray(new JOSEObjectType[types.size() + 1]);
        allowed[types.size()] = null; // tokens without a typ header
        return allowed;
    }

    private static URL toUrl(String uri) {
        try {
            return URI.create(uri).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("fintech.security.oidc.jwk-set-uri is not a valid URL", e);
        }
    }
}
