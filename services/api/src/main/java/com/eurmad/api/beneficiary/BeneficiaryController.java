package com.eurmad.api.beneficiary;

import com.eurmad.api.identity.AuthenticatedCustomer;
import com.eurmad.api.identity.CurrentCustomer;
import com.eurmad.api.error.ApiProblemException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Contract operations {@code listBeneficiaries}, {@code createBeneficiary}, {@code getBeneficiary}
 * and {@code deleteBeneficiary}. The owner is always the authenticated customer; no client-supplied
 * owner ID is accepted. Foreign, deleted and unknown beneficiaries are indistinguishable (404).
 */
@RestController
public class BeneficiaryController {

    private final BeneficiaryService service;

    BeneficiaryController(BeneficiaryService service) {
        this.service = service;
    }

    @GetMapping(path = "/v1/beneficiaries", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Beneficiary>> listBeneficiaries(@CurrentCustomer AuthenticatedCustomer customer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(customer.id()));
    }

    @PostMapping(path = "/v1/beneficiaries", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Beneficiary> createBeneficiary(
            @CurrentCustomer AuthenticatedCustomer customer,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) JsonNode body) {
        String key = BeneficiaryRules.requireIdempotencyKey(idempotencyKey);
        // Bound as a tree, not a record: record binding would coerce JSON numbers and booleans into
        // strings, whereas the contract requires every field to be a JSON string.
        if (body == null || !body.isObject()) {
            throw ApiProblemException.badRequest();
        }
        BeneficiaryRules.ValidCreate request = BeneficiaryRules.validateCreate(
                stringField(body, "displayName"), stringField(body, "country"),
                stringField(body, "payoutMethod"), stringField(body, "destinationTokenRef"));
        BeneficiaryService.Created created = service.create(customer.id(), key, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", created.correlationId().toString())
                .body(created.beneficiary());
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

    @GetMapping(path = "/v1/beneficiaries/{beneficiaryId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Beneficiary> getBeneficiary(
            @CurrentCustomer AuthenticatedCustomer customer, @PathVariable UUID beneficiaryId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.get(customer.id(), beneficiaryId));
    }

    @DeleteMapping(path = "/v1/beneficiaries/{beneficiaryId}")
    public ResponseEntity<Void> deleteBeneficiary(
            @CurrentCustomer AuthenticatedCustomer customer, @PathVariable UUID beneficiaryId) {
        UUID correlationId = service.delete(customer.id(), beneficiaryId);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", correlationId.toString())
                .build();
    }
}
