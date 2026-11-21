package com.eurmad.api.identity;

import java.util.Optional;

public interface CustomerRepository {

    /**
     * Looks up the customer bound to a verified OIDC identity. Both the issuer and the subject must
     * match exactly (case-sensitive); subjects are only unique within an issuer.
     */
    Optional<CustomerProfile> findByOidcIdentity(String oidcIssuer, String oidcSubject);
}
