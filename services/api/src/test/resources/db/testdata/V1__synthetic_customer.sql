INSERT INTO customers (id, oidc_subject, residency_country)
VALUES (
  '00000000-0000-4000-8000-000000000001',
  'test:fx-002:synthetic-customer-001',
  'ES'
)
ON CONFLICT (oidc_subject) DO NOTHING;
