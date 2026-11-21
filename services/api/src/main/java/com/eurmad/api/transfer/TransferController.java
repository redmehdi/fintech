package com.eurmad.api.transfer;

import com.eurmad.api.error.ApiProblemException;
import com.eurmad.api.error.ProblemErrorController.Problem;
import com.eurmad.api.error.ProblemResponses;
import com.eurmad.api.identity.AuthenticatedCustomer;
import com.eurmad.api.identity.CurrentCustomer;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Contract operations {@code createTransfer}, {@code listTransfers} and {@code getTransfer}. The
 * owner is always the authenticated customer; foreign and unknown transfers are indistinguishable
 * ({@code 404}). Simulation only: no request here moves or exchanges money.
 */
@RestController
public class TransferController {

    private final TransferService service;

    TransferController(TransferService service) {
        this.service = service;
    }

    @GetMapping(path = "/v1/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Transfer>> listTransfers(@CurrentCustomer AuthenticatedCustomer customer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(customer.id()));
    }

    @GetMapping(path = "/v1/transfers/{transferId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Transfer> getTransfer(
            @CurrentCustomer AuthenticatedCustomer customer, @PathVariable UUID transferId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(customer.id(), transferId));
    }

    /**
     * Eligibility is enforced by the service under the customer lock, after idempotent replays are
     * answered, so the handler takes {@link CurrentCustomer} rather than {@code EligibleCustomer}.
     */
    @PostMapping(path = "/v1/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Transfer> createTransfer(
            @CurrentCustomer AuthenticatedCustomer customer,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) JsonNode body) {
        String key = TransferRules.requireIdempotencyKey(idempotencyKey);
        // Bound as a tree so non-string JSON values are rejected rather than coerced.
        if (body == null || !body.isObject()) {
            throw ApiProblemException.badRequest();
        }
        TransferRules.ValidCreate request = TransferRules.validateCreate(
                stringField(body, "quoteId"), stringField(body, "beneficiaryId"), stringField(body, "purpose"));
        TransferService.Created created = service.create(customer.id(), key, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", created.correlationId().toString())
                .body(created.transfer());
    }

    /** A missing or non-JSON {@code Content-Type} gets the contract's {@code 400}, not Spring's {@code 415}. */
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
