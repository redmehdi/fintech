package com.eurmad.api.security;

import static com.eurmad.api.security.TestOidcIssuer.CUSTOMER_A_SUB;
import static com.eurmad.api.security.TestOidcIssuer.CUSTOMER_B_SUB;
import static com.eurmad.api.security.TestOidcIssuer.UNPROVISIONED_SUB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.eurmad.api.identity.AuthenticatedCustomer;
import com.eurmad.api.identity.CurrentCustomer;
import com.eurmad.api.identity.CustomerProfile;
import com.eurmad.api.identity.CustomerRepository;
import com.eurmad.api.identity.MeController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Security filter chain, sub→customer mapping and ownership, against the real JWT decoder. */
@WebMvcTest(controllers = {MeController.class, ResourceServerSecurityTest.OwnedResourceTestController.class})
@Import({SecurityConfig.class, ResourceServerSecurityTest.TestConfig.class,
        ResourceServerSecurityTest.OwnedResourceTestController.class})
class ResourceServerSecurityTest {

    private static final TestOidcIssuer ISSUER = new TestOidcIssuer();

    static final UUID CUSTOMER_A_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID CUSTOMER_B_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    static final UUID RESOURCE_OF_A = UUID.fromString("00000000-0000-4000-8000-00000000a001");

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry registry) {
        registry.add("fintech.security.oidc.issuer", () -> TestOidcIssuer.ISSUER);
        registry.add("fintech.security.oidc.jwk-set-uri", ISSUER::jwkSetUri);
        registry.add("fintech.security.oidc.audience", () -> TestOidcIssuer.AUDIENCE);
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private CustomerRepository customers;

    @BeforeEach
    void customers() {
        when(customers.findByOidcIdentity(anyString(), anyString())).thenReturn(Optional.empty());
        when(customers.findByOidcIdentity(TestOidcIssuer.ISSUER, CUSTOMER_A_SUB)).thenReturn(Optional.of(
                new CustomerProfile(CUSTOMER_A_ID, "ES", "APPROVED", "ACTIVE")));
        when(customers.findByOidcIdentity(TestOidcIssuer.ISSUER, CUSTOMER_B_SUB)).thenReturn(Optional.of(
                new CustomerProfile(CUSTOMER_B_ID, "MA", "NOT_STARTED", "ACTIVE")));
    }

    @Test
    void meReturnsCustomerMappedFromSubject() throws Exception {
        MockHttpServletResponse response = perform(get("/v1/me"), ISSUER.token(CUSTOMER_A_SUB));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertThat(body.path("id").stringValue()).isEqualTo(CUSTOMER_A_ID.toString());
        assertThat(body.path("residencyCountry").stringValue()).isEqualTo("ES");
        assertThat(body.path("kycStatus").stringValue()).isEqualTo("APPROVED");
        assertThat(body.path("status").stringValue()).isEqualTo("ACTIVE");
        assertThat(body.size()).isEqualTo(4);
        assertThat(response.getContentAsString()).doesNotContain(CUSTOMER_A_SUB);
    }

    @Test
    void eachSubjectSeesOnlyItsOwnProfile() throws Exception {
        JsonNode body = mapper.readTree(
                perform(get("/v1/me"), ISSUER.token(CUSTOMER_B_SUB)).getContentAsByteArray());
        assertThat(body.path("id").stringValue()).isEqualTo(CUSTOMER_B_ID.toString());
    }

    @Test
    void unprovisionedSubjectGetsNotFound() throws Exception {
        assertProblem(perform(get("/v1/me"), ISSUER.token(UNPROVISIONED_SUB)), 404, "NOT_FOUND");
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        MockHttpServletResponse response = perform(get("/v1/me"), null);
        assertProblem(response, 401, "UNAUTHORIZED");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        verify(customers, never()).findByOidcIdentity(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-jwt", "a.b.c", "eyJhbGciOiJSUzI1NiJ9.e30.", "Zm9v.YmFy.YmF6"})
    void malformedTokenIsUnauthorizedWithoutDetail(String token) throws Exception {
        MockHttpServletResponse response = perform(get("/v1/me"), token);
        assertProblem(response, 401, "UNAUTHORIZED");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(response.getContentAsString()).doesNotContain(token);
    }

    @Test
    void expiredTokenIsUnauthorizedWithoutLeakingValidationDetail() throws Exception {
        String token = ISSUER.token(CUSTOMER_A_SUB,
                c -> c.expirationTime(Date.from(Instant.now().minusSeconds(600))));
        MockHttpServletResponse response = perform(get("/v1/me"), token);
        assertProblem(response, 401, "UNAUTHORIZED");
        String header = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
        assertThat(header).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(response.getContentAsString()).doesNotContainIgnoringCase("expired");
    }

    @Test
    void wrongAudienceIsUnauthorized() throws Exception {
        String token = ISSUER.token(CUSTOMER_A_SUB, c -> c.audience(List.of("another-api")));
        assertProblem(perform(get("/v1/me"), token), 401, "UNAUTHORIZED");
    }

    @Test
    void wrongIssuerIsUnauthorized() throws Exception {
        String token = ISSUER.token(CUSTOMER_A_SUB, c -> c.issuer("https://evil.test.invalid"));
        assertProblem(perform(get("/v1/me"), token), 401, "UNAUTHORIZED");
    }

    @Test
    void forgedSignatureIsUnauthorized() throws Exception {
        String token = ISSUER.signWithForeignKey(TestOidcIssuer.claims(CUSTOMER_A_SUB).build());
        assertProblem(perform(get("/v1/me"), token), 401, "UNAUTHORIZED");
        verify(customers, never()).findByOidcIdentity(anyString(), anyString());
    }

    @Test
    void unsignedTokenIsUnauthorized() throws Exception {
        String token = TestOidcIssuer.unsigned(TestOidcIssuer.claims(CUSTOMER_A_SUB).build());
        assertProblem(perform(get("/v1/me"), token), 401, "UNAUTHORIZED");
    }

    @Test
    void tokenInQueryParameterIsNotAccepted() throws Exception {
        MockHttpServletResponse response =
                perform(get("/v1/me").param("access_token", ISSUER.token(CUSTOMER_A_SUB)), null);
        assertProblem(response, 401, "UNAUTHORIZED");
    }

    @Test
    void missingCustomerScopeIsForbidden() throws Exception {
        String token = ISSUER.token(CUSTOMER_A_SUB, c -> c.claim("scope", "openid profile"));
        MockHttpServletResponse response = perform(get("/v1/me"), token);
        assertProblem(response, 403, "FORBIDDEN");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"insufficient_scope\"");
    }

    @Test
    void roleClaimsDoNotGrantAccess() throws Exception {
        String token = ISSUER.token(CUSTOMER_A_SUB, c -> c.claim("scope", "openid")
                .claim("roles", List.of("ADMIN", "CUSTOMER"))
                .claim("authorities", List.of("SCOPE_" + SecurityConfig.CUSTOMER_SCOPE)));
        assertProblem(perform(get("/v1/me"), token), 403, "FORBIDDEN");
    }

    @Test
    void adminRoutesAreDeniedForCustomers() throws Exception {
        assertProblem(perform(get("/v1/admin/reviews"), ISSUER.token(CUSTOMER_A_SUB)), 403, "FORBIDDEN");
    }

    @Test
    void publicActuatorPathsPermitHeadWithoutCredentials() throws Exception {
        // Actuator is not part of this slice, so a permitted request falls through to 404.
        assertThat(perform(head("/actuator/health"), null).getStatus()).isEqualTo(404);
        assertThat(perform(head("/actuator/info"), null).getStatus()).isEqualTo(404);
        assertProblem(perform(head("/actuator/env"), null), 401, "UNAUTHORIZED");
        assertProblem(perform(post("/actuator/health"), null), 401, "UNAUTHORIZED");
    }

    @Test
    void unlistedRoutesAreDenied() throws Exception {
        assertProblem(perform(get("/internal/anything"), null), 401, "UNAUTHORIZED");
        assertProblem(perform(get("/internal/anything"), ISSUER.token(CUSTOMER_A_SUB)), 403, "FORBIDDEN");
    }

    @Test
    void ownerCanReadOwnResource() throws Exception {
        MockHttpServletResponse response =
                perform(get("/v1/test/owned/" + RESOURCE_OF_A), ISSUER.token(CUSTOMER_A_SUB));
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void crossAccountReadIsIndistinguishableFromMissing() throws Exception {
        MockHttpServletResponse foreign =
                perform(get("/v1/test/owned/" + RESOURCE_OF_A), ISSUER.token(CUSTOMER_B_SUB));
        MockHttpServletResponse missing =
                perform(get("/v1/test/owned/" + UUID.randomUUID()), ISSUER.token(CUSTOMER_B_SUB));
        assertProblem(foreign, 404, "NOT_FOUND");
        assertProblem(missing, 404, "NOT_FOUND");
        assertThat(foreign.getContentAsString()).doesNotContain(RESOURCE_OF_A.toString());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request.accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse();
    }

    private void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("code").stringValue()).isEqualTo(code);
        String correlationId = body.path("correlationId").stringValue();
        assertThat(UUID.fromString(correlationId).toString()).isEqualTo(correlationId);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(correlationId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OidcProperties.class)
    static class TestConfig {}

    /** Test-only stand-in for a customer-owned resource (e.g. beneficiary, transfer). */
    @RestController
    static class OwnedResourceTestController {
        private static final Map<UUID, UUID> OWNER_BY_RESOURCE = Map.of(RESOURCE_OF_A, CUSTOMER_A_ID);

        @GetMapping("/v1/test/owned/{id}")
        Map<String, String> read(@CurrentCustomer AuthenticatedCustomer caller, @PathVariable UUID id) {
            Ownership.requireOwner(caller, OWNER_BY_RESOURCE.get(id));
            return Map.of("id", id.toString());
        }
    }
}
