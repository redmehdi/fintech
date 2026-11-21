-- FX-005: KYC sandbox. SIMULATION ONLY.
-- kyc_cases stores only the provider name, the provider's opaque case reference and the decision.
-- Raw identity documents, images, document numbers or extracted PII are never stored here.

-- Only the deterministic sandbox provider may record cases. Adding a real verification provider
-- requires a reviewed migration together with the legal/partner milestone (fail closed).
ALTER TABLE kyc_cases
  ADD CONSTRAINT kyc_cases_sandbox_provider_only CHECK (provider = 'SANDBOX');

-- At most one in-flight verification per customer (defence in depth behind the row lock taken
-- by the application when a session is created).
CREATE UNIQUE INDEX ux_kyc_cases_one_pending_per_customer
  ON kyc_cases (customer_id) WHERE status = 'PENDING';

-- A decided case must carry its decision time; an undecided case must not.
ALTER TABLE kyc_cases
  ADD CONSTRAINT kyc_cases_decided_at_check CHECK ((status = 'PENDING') = (decided_at IS NULL));
