package com.eurmad.api.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * The local customer bound to the verified token subject of the current request. Authorization
 * decisions use {@link #id()}, never a client-supplied identifier.
 */
public record AuthenticatedCustomer(CustomerProfile profile) {

    public AuthenticatedCustomer {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(profile.id(), "profile.id");
    }

    public UUID id() {
        return profile.id();
    }
}
