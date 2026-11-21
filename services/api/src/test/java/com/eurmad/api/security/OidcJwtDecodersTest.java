package com.eurmad.api.security;

import static com.eurmad.api.security.TestOidcIssuer.CUSTOMER_A_SUB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Strict validation of OIDC access tokens; no Docker required. */
class OidcJwtDecodersTest {

    private static TestOidcIssuer issuer;
    private static JwtDecoder decoder;

    @BeforeAll
    static void start() {
        issuer = new TestOidcIssuer();
        decoder = OidcJwtDecoders.create(issuer.properties());
    }

    @AfterAll
    static void stop() {
        issuer.close();
    }

    @Test
    void acceptsValidToken() {
        Jwt jwt = decoder.decode(issuer.token(CUSTOMER_A_SUB));
        assertThat(jwt.getSubject()).isEqualTo(CUSTOMER_A_SUB);
        assertThat(jwt.getAudience()).contains(TestOidcIssuer.AUDIENCE);
    }

    @Test
    void acceptsAudienceListContainingApi() {
        String token = issuer.token(CUSTOMER_A_SUB,
                c -> c.audience(List.of("other-client", TestOidcIssuer.AUDIENCE)));
        assertThat(decoder.decode(token).getSubject()).isEqualTo(CUSTOMER_A_SUB);
    }

    @ParameterizedTest
    @ValueSource(strings = {"at+jwt", "application/at+jwt", "AT+JWT", "JWT"})
    void acceptsSupportedAccessTokenTypes(String type) {
        String token = issuer.signWithType(TestOidcIssuer.claims(CUSTOMER_A_SUB).build(), new JOSEObjectType(type));
        assertThat(decoder.decode(token).getSubject()).isEqualTo(CUSTOMER_A_SUB);
    }

    @Test
    void acceptsTokenWithoutTypeHeader() {
        String token = issuer.signWithType(TestOidcIssuer.claims(CUSTOMER_A_SUB).build(), null);
        assertThat(decoder.decode(token).getSubject()).isEqualTo(CUSTOMER_A_SUB);
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout+jwt", "secevent+jwt", "JOSE", "id_token+jwt"})
    void rejectsOtherJwtTypes(String type) {
        String token = issuer.signWithType(TestOidcIssuer.claims(CUSTOMER_A_SUB).build(), new JOSEObjectType(type));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void unknownKeyIdsCannotDriveUnboundedJwksRefreshes() {
        try (TestOidcIssuer isolated = new TestOidcIssuer()) {
            JwtDecoder fresh = OidcJwtDecoders.create(isolated.properties());
            fresh.decode(isolated.token(CUSTOMER_A_SUB)); // initial fetch populates the cache
            int afterWarmup = isolated.jwksRequestCount();

            for (int i = 0; i < 20; i++) {
                String forged = TestOidcIssuer.signWithUnknownKid(TestOidcIssuer.claims(CUSTOMER_A_SUB).build());
                assertThatThrownBy(() -> fresh.decode(forged)).isInstanceOf(JwtException.class);
            }

            // Rate limiter permits at most a couple of fetches per interval, not one per token.
            assertThat(isolated.jwksRequestCount() - afterWarmup).isLessThanOrEqualTo(2);
            // Known signers are still served from the cache while refreshes are throttled.
            assertThat(fresh.decode(isolated.token(TestOidcIssuer.CUSTOMER_B_SUB)).getSubject())
                    .isEqualTo(TestOidcIssuer.CUSTOMER_B_SUB);
        }
    }

    static Stream<Arguments> invalidClaims() {
        Instant now = Instant.now();
        return Stream.of(
                Arguments.of("expired", (ClaimsEdit) c -> c.expirationTime(Date.from(now.minusSeconds(120)))),
                Arguments.of("missing exp", (ClaimsEdit) c -> c.expirationTime(null)),
                Arguments.of("not yet valid", (ClaimsEdit) c -> c.notBeforeTime(Date.from(now.plusSeconds(600)))),
                Arguments.of("wrong issuer", (ClaimsEdit) c -> c.issuer("https://evil.test.invalid/realms/eurmad-sim")),
                Arguments.of("issuer trailing slash", (ClaimsEdit) c -> c.issuer(TestOidcIssuer.ISSUER + "/")),
                Arguments.of("missing issuer", (ClaimsEdit) c -> c.issuer(null)),
                Arguments.of("wrong audience", (ClaimsEdit) c -> c.audience(List.of("another-api"))),
                Arguments.of("missing audience", (ClaimsEdit) c -> c.audience((String) null)),
                Arguments.of("missing subject", (ClaimsEdit) c -> c.subject(null)),
                Arguments.of("blank subject", (ClaimsEdit) c -> c.subject("  ")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidClaims")
    void rejectsInvalidClaims(String name, ClaimsEdit edit) {
        String token = issuer.sign(edit.apply(TestOidcIssuer.claims(CUSTOMER_A_SUB)).build());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsSignatureFromUntrustedKey() {
        String token = issuer.signWithForeignKey(TestOidcIssuer.claims(CUSTOMER_A_SUB).build());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnsignedToken() {
        String token = TestOidcIssuer.unsigned(TestOidcIssuer.claims(CUSTOMER_A_SUB).build());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsHmacAlgorithmConfusion() {
        String token = issuer.signHs256WithPublicKeyMaterial(TestOidcIssuer.claims(CUSTOMER_A_SUB).build());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTamperedPayload() {
        String[] parts = issuer.token(CUSTOMER_A_SUB).split("\\.");
        String forgedPayload = com.nimbusds.jose.util.Base64URL.encode(
                TestOidcIssuer.claims(TestOidcIssuer.CUSTOMER_B_SUB).build().toString()).toString();
        String token = parts[0] + "." + forgedPayload + "." + parts[2];
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-jwt", "a.b.c", "eyJhbGciOiJSUzI1NiJ9..", "....."})
    void rejectsMalformedTokens(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void propertiesFailClosed() {
        String jwks = issuer.jwkSetUri();
        Duration skew = Duration.ofSeconds(30);
        assertThatThrownBy(() -> new OidcProperties(null, jwks, "aud", skew))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OidcProperties("iss", jwks, " ", skew))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OidcProperties("iss", "http://idp.example.test/jwks", "aud", skew))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OidcProperties("iss", "file:///etc/jwks.json", "aud", skew))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OidcProperties("iss", jwks, "aud", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new OidcProperties("iss", "https://idp.example.test/jwks", "aud", skew).audience())
                .isEqualTo("aud");
    }

    @FunctionalInterface
    interface ClaimsEdit {
        JWTClaimsSet.Builder apply(JWTClaimsSet.Builder builder);
    }
}
