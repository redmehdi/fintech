package com.eurmad.api.identity;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract operation {@code getCurrentCustomer}. */
@RestController
public class MeController {

    @GetMapping(path = "/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerProfile> getCurrentCustomer(@CurrentCustomer AuthenticatedCustomer customer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(customer.profile());
    }
}
