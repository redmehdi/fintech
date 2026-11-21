package com.eurmad.api.kyc;

import com.eurmad.api.config.OperatingMode;
import com.eurmad.api.error.ApiProblemException;
import com.eurmad.api.identity.AuthenticatedCustomer;
import com.eurmad.api.identity.CurrentCustomer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract operations {@code getKycStatus} and {@code createKycSession}. Both act only on the
 * caller's own customer record; session creation rejects any request body with 400, before any
 * state change, so no document data can be submitted to or stored by this API.
 */
@RestController
public class KycController {

    private final KycService service;

    KycController(KycService service) {
        this.service = service;
    }

    record KycStatusResponse(String status, OperatingMode mode) {}

    record KycSessionResponse(UUID id, VerificationDecision.Outcome status, OperatingMode mode) {}

    @GetMapping(path = "/v1/kyc/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KycStatusResponse> getKycStatus(@CurrentCustomer AuthenticatedCustomer customer) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new KycStatusResponse(customer.profile().kycStatus(), OperatingMode.SIMULATION));
    }

    @PostMapping(path = "/v1/kyc/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KycSessionResponse> createKycSession(
            @CurrentCustomer AuthenticatedCustomer customer, HttpServletRequest request) {
        if (hasBody(request)) {
            // Neither read, parsed nor logged: the body may contain identity data.
            throw ApiProblemException.badRequest();
        }
        KycService.KycCase kycCase = service.startVerification(customer.id());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", kycCase.correlationId().toString())
                .body(new KycSessionResponse(kycCase.id(), kycCase.status(), OperatingMode.SIMULATION));
    }

    /** True when the request declares a non-empty body (fixed length or chunked). */
    static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
    }
}
