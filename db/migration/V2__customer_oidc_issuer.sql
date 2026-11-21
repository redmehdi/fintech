-- FX-004: bind each customer to the verified OIDC (iss, sub) pair. SIMULATION ONLY.
-- OIDC subjects are unique only within an issuer, so lookups must match both values.
-- Existing rows keep oidc_issuer NULL and therefore cannot be resolved from any token until an
-- operator explicitly assigns their issuer (fail closed after an issuer change).
-- The existing UNIQUE (oidc_subject) constraint is retained: a single trusted issuer is supported in
-- the MVP, and a subject reused by a new issuer cannot be provisioned until old rows are migrated.
ALTER TABLE customers
  ADD COLUMN oidc_issuer TEXT CHECK (oidc_issuer IS NULL OR length(btrim(oidc_issuer)) > 0);

ALTER TABLE customers
  ADD CONSTRAINT customers_oidc_identity_key UNIQUE (oidc_issuer, oidc_subject);
