package com.eurmad.api.identity;

import com.eurmad.api.error.ApiProblemException;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves {@link CurrentCustomer} parameters from the verified JWT issuer and subject. */
class CurrentCustomerArgumentResolver implements HandlerMethodArgumentResolver {

    private final CustomerRepository customers;

    CurrentCustomerArgumentResolver(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentCustomer.class)
                && AuthenticatedCustomer.class.equals(parameter.getParameterType());
    }

    @Override
    public AuthenticatedCustomer resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Fail closed: only a verified bearer JWT can identify a customer.
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth) || !jwtAuth.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Bearer token required");
        }
        // iss was already checked against the configured issuer by the decoder; bind on (iss, sub).
        String issuer = jwtAuth.getToken().getClaimAsString(JwtClaimNames.ISS);
        String subject = jwtAuth.getToken().getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new AuthenticationCredentialsNotFoundException("Token issuer and subject required");
        }
        return customers.findByOidcIdentity(issuer, subject)
                .map(AuthenticatedCustomer::new)
                .orElseThrow(ApiProblemException::notFound);
    }
}
