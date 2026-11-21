package com.eurmad.api.security;

import com.eurmad.api.error.ApiProblemException;
import com.eurmad.api.identity.AuthenticatedCustomer;
import java.util.UUID;

/**
 * Row-ownership guard against BOLA/IDOR.
 *
 * <p>Repositories must scope reads/writes of customer-owned rows with a {@code customer_id = ?}
 * SQL predicate; services additionally call {@link #requireOwner} on any loaded row. A foreign or
 * missing resource yields the same 404 so resource existence is not disclosed.
 */
public final class Ownership {

    private Ownership() {}

    public static void requireOwner(AuthenticatedCustomer caller, UUID resourceOwnerId) {
        if (caller == null || resourceOwnerId == null || !resourceOwnerId.equals(caller.id())) {
            throw ApiProblemException.notFound();
        }
    }
}
