package com.eurmad.api.kyc;

import java.util.UUID;

/**
 * Port to an identity-verification (KYC) provider.
 *
 * <p>The application passes only an opaque case ID: no name, document, image or other PII is sent
 * through or stored by this port. Document capture happens entirely at the provider. The MVP binds
 * only {@link SandboxIdentityVerificationProvider}; a real provider requires a reviewed migration
 * (the {@code kyc_cases} provider check) and the legal/partner milestone.
 */
public interface IdentityVerificationProvider {

    /** Stable provider identifier persisted in {@code kyc_cases.provider}. */
    String name();

    /** Starts a verification for the given local case. Must be deterministic in the sandbox. */
    VerificationDecision startVerification(UUID kycCaseId);
}
