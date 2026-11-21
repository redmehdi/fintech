package com.eurmad.api.beneficiary;

/**
 * Trusted issuer of opaque destination tokens. Raw banking details never reach this API: a
 * destination is tokenized by the issuer, and the API accepts only tokens that the issuer confirms
 * it issued for the requested country and payout method. Holding a token does not grant access to
 * the token's underlying destination.
 */
public interface DestinationTokenRegistry {

    /** Whether {@code token} was issued for exactly this {@code country} and {@code payoutMethod}. */
    boolean isIssued(String token, String country, String payoutMethod);
}
