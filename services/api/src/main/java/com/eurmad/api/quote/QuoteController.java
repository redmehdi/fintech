package com.eurmad.api.quote;

import com.eurmad.api.error.ApiProblemException;
import com.eurmad.api.error.ProblemErrorController.Problem;
import com.eurmad.api.error.ProblemResponses;
import com.eurmad.api.identity.AuthenticatedCustomer;
import com.eurmad.api.identity.EligibleCustomer;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Contract operation {@code createQuote}. The owner is always the authenticated, eligible customer;
 * no client-supplied owner, rate, fee or expiry is accepted.
 */
@RestController
public class QuoteController {

    private final QuoteService service;

    QuoteController(QuoteService service) {
        this.service = service;
    }

    @PostMapping(path = "/v1/quotes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Quote> createQuote(
            @EligibleCustomer AuthenticatedCustomer customer,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) JsonNode body) {
        String key = QuoteRules.requireIdempotencyKey(idempotencyKey);
        // Bound as a tree so JSON numbers are rejected rather than coerced: amounts must be strings.
        if (body == null || !body.isObject()) {
            throw ApiProblemException.badRequest();
        }
        QuoteRules.ValidRequest request = QuoteRules.validate(
                stringField(body, "sourceCurrency"), stringField(body, "targetCurrency"),
                stringField(body, "sourceAmountMinor"), stringField(body, "purpose"));
        QuoteService.Issued issued = service.create(customer.id(), key, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", issued.correlationId().toString())
                .body(issued.quote());
    }

    /**
     * The contract documents {@code 400} for a non-JSON body, so a missing or non-JSON {@code
     * Content-Type} gets the shared {@code 400} envelope rather than Spring's {@code 415}. Scoped to
     * this controller; nothing from the request is logged or echoed.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Problem> handleUnsupportedMediaType(HttpServletResponse response) {
        return ProblemResponses.response(response, HttpStatus.BAD_REQUEST.value(), UUID.randomUUID());
    }

    /** The field's value if it is a JSON string; {@code null} if absent or JSON null (later 400). */
    private static String stringField(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw ApiProblemException.badRequest();
        }
        return value.stringValue();
    }
}
