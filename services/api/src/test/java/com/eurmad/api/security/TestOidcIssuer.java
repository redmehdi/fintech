package com.eurmad.api.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Synthetic OIDC issuer for tests: serves a JWKS on a loopback port and mints signed tokens. All
 * subjects and keys are generated per test run; nothing here is a real credential.
 */
public final class TestOidcIssuer implements AutoCloseable {

    public static final String ISSUER = "https://idp.test.invalid/realms/eurmad-sim";
    public static final String AUDIENCE = "eurmad-api";
    public static final String CUSTOMER_A_SUB = "test:fx-004:synthetic-customer-a";
    public static final String CUSTOMER_B_SUB = "test:fx-004:synthetic-customer-b";
    public static final String UNPROVISIONED_SUB = "test:fx-004:synthetic-unprovisioned";

    private final RSAKey signingKey;
    private final RSAKey foreignKey;
    private final HttpServer server;
    private final AtomicInteger jwksRequests = new AtomicInteger();

    public TestOidcIssuer() {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("fx004-test-key").generate();
            foreignKey = new RSAKeyGenerator(2048).keyID("fx004-test-key").generate();
            byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/jwks", exchange -> {
                jwksRequests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            server.start();
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String jwkSetUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    /** Number of JWKS fetches served so far. */
    public int jwksRequestCount() {
        return jwksRequests.get();
    }

    public OidcProperties properties() {
        return new OidcProperties(ISSUER, jwkSetUri(), AUDIENCE, Duration.ofSeconds(30));
    }

    /** Valid claims for the given subject with the customer scope. */
    public static JWTClaimsSet.Builder claims(String subject) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(subject)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .claim("scope", SecurityConfig.CUSTOMER_SCOPE + " openid");
    }

    public String token(String subject) {
        return sign(claims(subject).build());
    }

    public String token(String subject, UnaryOperator<JWTClaimsSet.Builder> customizer) {
        return sign(customizer.apply(claims(subject)).build());
    }

    public String sign(JWTClaimsSet claims) {
        return sign(claims, signingKey);
    }

    /** Trusted key with an explicit JOSE {@code typ} header. */
    public String signWithType(JWTClaimsSet claims, JOSEObjectType type) {
        return sign(claims, signingKey, type);
    }

    /** Fresh key pair whose kid is not in the JWKS: forces the decoder to consider a JWKS refresh. */
    public static String signWithUnknownKid(JWTClaimsSet claims) {
        try {
            return sign(claims, new RSAKeyGenerator(2048).keyID("unknown-" + UUID.randomUUID()).generate(), null);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Same kid as the trusted key but a different key pair: signature must not verify. */
    public String signWithForeignKey(JWTClaimsSet claims) {
        return sign(claims, foreignKey);
    }

    /** HMAC token keyed with the public modulus (classic alg-confusion attempt). */
    public String signHs256WithPublicKeyMaterial(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new MACSigner(signingKey.toPublicJWK().getModulus().decode()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Unsigned token ({@code alg=none}). */
    public static String unsigned(JWTClaimsSet claims) {
        String header = Base64URL.encode("{\"alg\":\"none\",\"typ\":\"JWT\"}").toString();
        String payload = Base64URL.encode(claims.toString()).toString();
        return header + "." + payload + ".";
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        return sign(claims, key, null);
    }

    private static String sign(JWTClaimsSet claims, RSAKey key, JOSEObjectType type) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(type).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
