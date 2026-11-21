-- FX-004 synthetic identities. Subjects, IDs and destinations are fabricated test data only.
-- Issuer values match TestOidcIssuer.ISSUER (a non-resolvable .invalid host).
INSERT INTO customers (id, oidc_issuer, oidc_subject, residency_country, kyc_status)
VALUES
  ('00000000-0000-4000-8000-0000000000a1', 'https://idp.test.invalid/realms/eurmad-sim',
   'test:fx-004:synthetic-customer-a', 'ES', 'APPROVED'),
  ('00000000-0000-4000-8000-0000000000b1', 'https://idp.test.invalid/realms/eurmad-sim',
   'test:fx-004:synthetic-customer-b', 'MA', 'NOT_STARTED'),
  -- Same subject shape bound to a previous issuer: must never resolve from the current issuer.
  ('00000000-0000-4000-8000-0000000000c1', 'https://old-idp.test.invalid/realms/eurmad-sim',
   'test:fx-004:synthetic-customer-previous-issuer', 'ES', 'APPROVED')
ON CONFLICT (oidc_subject) DO NOTHING;

INSERT INTO beneficiaries (id, customer_id, display_name, country, payout_method,
                           destination_token_ref, masked_destination)
VALUES ('00000000-0000-4000-8000-00000000a001', '00000000-0000-4000-8000-0000000000a1',
        'Synthetic Beneficiary A', 'MA', 'BANK_ACCOUNT', 'sim-token:fx-004:a001', '****0001')
ON CONFLICT (id) DO NOTHING;
