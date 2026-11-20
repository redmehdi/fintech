-- Baseline development schema. SIMULATION ONLY. Review with financial/security counsel before real money.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE customers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  oidc_subject TEXT NOT NULL UNIQUE,
  email TEXT,
  residency_country CHAR(2) NOT NULL CHECK (residency_country IN ('ES','MA')),
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
  kyc_status TEXT NOT NULL DEFAULT 'NOT_STARTED' CHECK (kyc_status IN ('NOT_STARTED','PENDING','APPROVED','REJECTED','EXPIRED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kyc_cases (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id UUID NOT NULL REFERENCES customers(id),
  provider TEXT NOT NULL,
  provider_case_ref TEXT,
  status TEXT NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED')),
  decision_reason_code TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at TIMESTAMPTZ,
  UNIQUE (provider, provider_case_ref)
);

CREATE TABLE beneficiaries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id UUID NOT NULL REFERENCES customers(id),
  display_name TEXT NOT NULL,
  country CHAR(2) NOT NULL CHECK (country IN ('ES','MA')),
  payout_method TEXT NOT NULL CHECK (payout_method IN ('BANK_ACCOUNT','PARTNER_CASH_PICKUP')),
  destination_token_ref TEXT NOT NULL, -- Vault/PSP token, not plain banking data
  masked_destination TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ
);

CREATE TABLE fx_quotes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id UUID NOT NULL REFERENCES customers(id),
  quote_type TEXT NOT NULL DEFAULT 'TRANSFER' CHECK (quote_type IN ('TRANSFER')),
  source_currency CHAR(3) NOT NULL CHECK (source_currency IN ('EUR','MAD')),
  target_currency CHAR(3) NOT NULL CHECK (target_currency IN ('EUR','MAD')),
  source_amount_minor BIGINT NOT NULL CHECK (source_amount_minor > 0),
  fee_source_minor BIGINT NOT NULL CHECK (fee_source_minor >= 0),
  total_debit_minor BIGINT NOT NULL CHECK (total_debit_minor = source_amount_minor + fee_source_minor),
  target_amount_minor BIGINT NOT NULL CHECK (target_amount_minor > 0),
  rate NUMERIC(22,12) NOT NULL CHECK (rate > 0),
  rate_source TEXT NOT NULL,
  mode TEXT NOT NULL DEFAULT 'SIMULATION' CHECK (mode = 'SIMULATION'),
  status TEXT NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED','CONSUMED','EXPIRED')),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fx_quotes_distinct_pair CHECK (source_currency <> target_currency)
);

CREATE TABLE transfers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id UUID NOT NULL REFERENCES customers(id),
  quote_id UUID NOT NULL UNIQUE REFERENCES fx_quotes(id),
  beneficiary_id UUID NOT NULL REFERENCES beneficiaries(id),
  purpose TEXT NOT NULL CHECK (purpose IN ('FAMILY_SUPPORT','PERSONAL_TRANSFER','EDUCATION','OTHER_REVIEW')),
  status TEXT NOT NULL DEFAULT 'CREATED' CHECK (status IN (
     'CREATED','AWAITING_KYC','AWAITING_FUNDING','FUNDING_CONFIRMED','PROCESSING',
     'REVIEW_REQUIRED','COMPLETED','REJECTED','CANCELLED','FAILED','REFUND_PENDING','REFUNDED'
  )),
  provider TEXT NOT NULL DEFAULT 'SIMULATOR',
  provider_transfer_ref TEXT,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, provider_transfer_ref)
);

CREATE TABLE fx_offers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id UUID NOT NULL REFERENCES customers(id),
  source_currency CHAR(3) NOT NULL CHECK (source_currency IN ('EUR','MAD')),
  target_currency CHAR(3) NOT NULL CHECK (target_currency IN ('EUR','MAD')),
  source_total_minor BIGINT NOT NULL CHECK (source_total_minor > 0),
  source_reserved_minor BIGINT NOT NULL DEFAULT 0 CHECK (source_reserved_minor >= 0),
  source_filled_minor BIGINT NOT NULL DEFAULT 0 CHECK (source_filled_minor >= 0),
  limit_rate NUMERIC(22,12) NOT NULL CHECK (limit_rate > 0),
  status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESERVED','MATCHED','CANCELLED','EXPIRED')),
  expires_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fx_offers_pair_check CHECK (source_currency <> target_currency),
  CONSTRAINT fx_offers_inventory_check CHECK (source_reserved_minor + source_filled_minor <= source_total_minor)
);

CREATE TABLE fx_matches (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  offer_a_id UUID NOT NULL REFERENCES fx_offers(id),
  offer_b_id UUID NOT NULL REFERENCES fx_offers(id),
  source_a_reserved_minor BIGINT NOT NULL CHECK (source_a_reserved_minor > 0),
  source_b_reserved_minor BIGINT NOT NULL CHECK (source_b_reserved_minor > 0),
  canonical_mad_per_eur_rate NUMERIC(22,12) NOT NULL CHECK (canonical_mad_per_eur_rate > 0),
  accepted_a BOOLEAN NOT NULL DEFAULT false,
  accepted_b BOOLEAN NOT NULL DEFAULT false,
  status TEXT NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','AGREED_SIMULATION_ONLY','DECLINED','EXPIRED')),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fx_matches_distinct_offers CHECK (offer_a_id <> offer_b_id)
);

CREATE TABLE idempotency_keys (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id UUID NOT NULL REFERENCES customers(id),
  operation TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  request_sha256 CHAR(64) NOT NULL,
  resource_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (customer_id, operation, idempotency_key)
);

CREATE TABLE webhook_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider TEXT NOT NULL,
  provider_event_id TEXT NOT NULL,
  payload_sha256 CHAR(64) NOT NULL,
  verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  result TEXT NOT NULL DEFAULT 'RECEIVED' CHECK (result IN ('RECEIVED','PROCESSED','DUPLICATE','REQUIRES_REVIEW','REJECTED')),
  UNIQUE (provider, provider_event_id)
);

CREATE TABLE transaction_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  resource_kind TEXT NOT NULL CHECK (resource_kind IN ('TRANSFER','FX_OFFER','FX_MATCH')),
  resource_id UUID NOT NULL,
  previous_status TEXT,
  next_status TEXT NOT NULL,
  actor_type TEXT NOT NULL CHECK (actor_type IN ('CUSTOMER','SYSTEM','PROVIDER','OPERATOR')),
  actor_ref TEXT NOT NULL,
  reason_code TEXT,
  correlation_id UUID NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_type TEXT NOT NULL,
  actor_ref TEXT NOT NULL,
  action TEXT NOT NULL,
  resource_type TEXT NOT NULL,
  resource_id TEXT NOT NULL,
  safe_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  aggregate_type TEXT NOT NULL,
  aggregate_id UUID NOT NULL,
  event_type TEXT NOT NULL,
  payload JSONB NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_PROGRESS','DELIVERED','DEAD_LETTER')),
  attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  delivered_at TIMESTAMPTZ
);

CREATE INDEX ix_beneficiaries_customer ON beneficiaries (customer_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_fx_quotes_customer_expiry ON fx_quotes (customer_id, expires_at);
CREATE INDEX ix_transfers_customer_created ON transfers (customer_id, created_at DESC);
CREATE INDEX ix_fx_offers_book ON fx_offers (source_currency, target_currency, status, expires_at);
CREATE INDEX ix_fx_offers_customer ON fx_offers (customer_id, created_at DESC);
CREATE INDEX ix_fx_matches_a ON fx_matches (offer_a_id);
CREATE INDEX ix_fx_matches_b ON fx_matches (offer_b_id);
CREATE INDEX ix_transaction_events_resource ON transaction_events (resource_kind, resource_id, recorded_at);
CREATE INDEX ix_outbox_dispatch ON outbox_events (status, next_attempt_at);
CREATE INDEX ix_kyc_customer ON kyc_cases (customer_id, created_at DESC);

-- Important: application services must validate cross-table ownership, counterparties,
-- canonical FX compatibility and allowed transitions within transactions.
-- The schema intentionally has no real-money ledger or provider credential tables.
