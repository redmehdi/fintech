package com.eurmad.api.error;

import com.eurmad.api.error.ProblemErrorController.Problem;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiProblemExceptionHandler {

    /** Seconds a client should wait before retrying a {@code 503}. */
    static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<Problem> handle(ApiProblemException exception, HttpServletResponse response) {
        if (exception.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return ProblemResponses.response(response, exception.status().value(), UUID.randomUUID());
    }

    /**
     * Unparseable request bodies get the fixed {@code 400}. Handling them here keeps Spring's
     * default resolver from logging the parser message, which can quote fragments of the submitted
     * body (destination tokens, names). Neither the exception nor its message is logged.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> handleUnreadable(HttpServletResponse response) {
        return ProblemResponses.response(response, HttpStatus.BAD_REQUEST.value(), UUID.randomUUID());
    }
}
