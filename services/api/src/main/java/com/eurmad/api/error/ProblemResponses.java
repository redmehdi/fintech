package com.eurmad.api.error;

import com.eurmad.api.error.ProblemErrorController.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Builds the shared {@code application/problem+json} envelope with fixed, client-safe messages and a
 * server-generated correlation ID (never echoed from the request).
 */
public final class ProblemResponses {

    static final String SERVER_ERROR_MESSAGE = "An unexpected server error occurred.";

    private ProblemResponses() {}

    public static Problem problem(int statusCode, UUID correlationId) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        String code = status == null ? "HTTP_" + statusCode : status.name();
        String message = status == null ? "Request failed" : status.getReasonPhrase();
        if (statusCode >= 500) {
            message = SERVER_ERROR_MESSAGE;
        }
        return new Problem(code, message, correlationId);
    }

    /**
     * Cache-Control is set on the servlet response (replacing any value) rather than on the entity:
     * on error dispatch Spring Security has already written its own cache headers, and entity
     * headers are appended, which would produce a duplicated Cache-Control value.
     */
    public static ResponseEntity<Problem> response(
            HttpServletResponse servletResponse, int statusCode, UUID correlationId) {
        servletResponse.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("X-Correlation-Id", correlationId.toString())
                .body(problem(statusCode, correlationId));
    }

    /** Writes the envelope directly, for filters that run outside Spring MVC (e.g. security). */
    public static void write(HttpServletResponse response, HttpStatus status, ObjectMapper mapper)
            throws IOException {
        UUID correlationId = UUID.randomUUID();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("X-Correlation-Id", correlationId.toString());
        mapper.writeValue(response.getOutputStream(), problem(status.value(), correlationId));
    }
}
