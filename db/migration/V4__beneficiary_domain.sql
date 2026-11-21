-- FX-006: beneficiary domain. SIMULATION ONLY.
-- A beneficiary stores an opaque destination token issued by the trusted sandbox (never raw
-- banking data) and a masked display value derived from that token. Deletion is soft
-- (deleted_at) so historical transfers keep their reference; deleted rows are never served.
--
-- V1-V3 placed no format rules on these columns, so rows already present (active or soft-deleted)
-- are brought into line first; otherwise one non-conforming row would abort this migration.
-- Every row keeps its ID, so transfers and audit events that reference it stay valid.

-- 1. Rows whose token is not a well-formed sandbox token may hold raw banking data. They are
--    quarantined: soft-deleted (never served or referenced again), the token replaced by an inert
--    sandbox placeholder, and only a SHA-256 of the original token kept for traceability.
CREATE TABLE beneficiary_token_quarantine (
  beneficiary_id UUID PRIMARY KEY REFERENCES beneficiaries(id),
  original_token_sha256 CHAR(64) NOT NULL,
  reason TEXT NOT NULL CHECK (reason IN ('NON_SANDBOX_TOKEN')),
  quarantined_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO beneficiary_token_quarantine (beneficiary_id, original_token_sha256, reason)
SELECT id, encode(digest(destination_token_ref, 'sha256'), 'hex'), 'NON_SANDBOX_TOKEN'
  FROM beneficiaries
 WHERE NOT (destination_token_ref ~ '^sim-token:[A-Za-z0-9._:-]{4,100}$'
            AND destination_token_ref !~ '[0-9]{9}');

UPDATE beneficiaries b
   SET destination_token_ref = 'sim-token:quarantined',
       deleted_at = coalesce(b.deleted_at, greatest(now(), b.created_at))
  FROM beneficiary_token_quarantine q
 WHERE q.beneficiary_id = b.id;

-- 2. Backfill derivable values for the remaining (sandbox-token) rows.
UPDATE beneficiaries
   SET masked_destination = '****' || right(destination_token_ref, 4)
 WHERE masked_destination IS DISTINCT FROM '****' || right(destination_token_ref, 4);

UPDATE beneficiaries
   SET display_name = CASE
         WHEN btrim(display_name) = '' THEN 'Unnamed beneficiary'
         ELSE left(btrim(display_name), 120)
       END
 WHERE display_name <> btrim(display_name) OR char_length(display_name) > 120;

UPDATE beneficiaries SET deleted_at = created_at WHERE deleted_at < created_at;

-- 3. Constraints, validated against every row (all rows now conform).
ALTER TABLE beneficiaries
  ADD CONSTRAINT beneficiaries_display_name_check
    CHECK (char_length(display_name) BETWEEN 1 AND 120 AND btrim(display_name) <> '');

-- Only sandbox-issued destination tokens may be recorded (same format rule as the API, including
-- no run of nine or more digits). Accepting a regulated PSP token requires a reviewed migration
-- together with the legal/partner milestone (fail closed).
ALTER TABLE beneficiaries
  ADD CONSTRAINT beneficiaries_sandbox_token_only
    CHECK (destination_token_ref ~ '^sim-token:[A-Za-z0-9._:-]{4,100}$'
           AND destination_token_ref !~ '[0-9]{9}');

-- The masked display is four mask characters followed by at most four token characters.
ALTER TABLE beneficiaries
  ADD CONSTRAINT beneficiaries_masked_destination_check
    CHECK (masked_destination ~ '^\*{4}[A-Za-z0-9._:-]{1,4}$');

ALTER TABLE beneficiaries
  ADD CONSTRAINT beneficiaries_deleted_after_created
    CHECK (deleted_at IS NULL OR deleted_at >= created_at);

-- Active-list lookups are ordered by creation time.
DROP INDEX IF EXISTS ix_beneficiaries_customer;
CREATE INDEX ix_beneficiaries_customer
  ON beneficiaries (customer_id, created_at, id) WHERE deleted_at IS NULL;
