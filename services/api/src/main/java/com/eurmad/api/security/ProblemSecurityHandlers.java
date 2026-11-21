package com.eurmad.api.security;

import com.eurmad.api.error.ProblemResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Renders authentication (401) and authorization (403) failures as the shared Problem envelope.
 *
 * <p>The {@code WWW-Authenticate} header carries only a fixed RFC 6750 error code; validation
 * detail (expiry instant, expected issuer, etc.) and token content are never echoed or logged.
 */
final class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemSecurityHandlers.class);

    private final ObjectMapper mapper;

    ProblemSecurityHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String challenge = "Bearer";
        if (exception instanceof OAuth2AuthenticationException oauth) {
            String code = OAuth2ErrorCodes.INVALID_REQUEST.equals(oauth.getError().getErrorCode())
                    ? OAuth2ErrorCodes.INVALID_REQUEST
                    : OAuth2ErrorCodes.INVALID_TOKEN;
            challenge = "Bearer error=\"" + code + "\"";
        }
        LOG.debug("Authentication rejected type={}", exception.getClass().getSimpleName());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        ProblemResponses.write(response, HttpStatus.UNAUTHORIZED, mapper);
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
        ProblemResponses.write(response, HttpStatus.FORBIDDEN, mapper);
    }
}
