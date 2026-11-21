-- FX-008: transfer orchestration. SIMULATION ONLY.
-- A transfer consumes exactly one fresh quote owned by the same customer and references one of
-- that customer's active beneficiaries. Its status follows the documented state machine
-- (docs/ARCHITECTURE.md §5); every other change is rejected here as well as in the application.
-- No funds are held, moved or exchanged: statuses are workflow facts, not balances.
--
-- No application path wrote transfers before V6. Pre-existing rows are kept for traceability; the
-- new checks are added NOT VALID (they apply to every row inserted or updated from now on) and any
-- later update of a legacy row is still bound by the transition guard below.

-- Only the deterministic simulator may be bound to a transfer. Accepting a licensed partner
-- requires a reviewed migration together with the legal/partner milestone (fail closed).
ALTER TABLE transfers
  ADD CONSTRAINT transfers_simulator_only CHECK (provider = 'SIMULATOR') NOT VALID;

ALTER TABLE transfers
  ADD CONSTRAINT transfers_updated_after_created CHECK (updated_at >= created_at) NOT VALID;

-- Permitted status transitions; must match TransferStatus in the API.
CREATE FUNCTION transfer_transition_allowed(from_status TEXT, to_status TEXT) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE AS $$
  SELECT (from_status, to_status) IN (
    ('CREATED', 'AWAITING_KYC'),
    ('CREATED', 'AWAITING_FUNDING'),
    ('AWAITING_KYC', 'AWAITING_FUNDING'),
    ('AWAITING_KYC', 'REJECTED'),
    ('AWAITING_FUNDING', 'FUNDING_CONFIRMED'),
    ('AWAITING_FUNDING', 'CANCELLED'),
    ('FUNDING_CONFIRMED', 'PROCESSING'),
    ('FUNDING_CONFIRMED', 'REFUND_PENDING'),
    ('PROCESSING', 'COMPLETED'),
    ('PROCESSING', 'REVIEW_REQUIRED'),
    ('PROCESSING', 'FAILED'),
    ('PROCESSING', 'REFUND_PENDING'),
    ('REVIEW_REQUIRED', 'PROCESSING'),
    ('REVIEW_REQUIRED', 'FAILED'),
    ('FAILED', 'REFUND_PENDING'),
    ('REFUND_PENDING', 'REFUNDED'))
$$;

CREATE FUNCTION transfers_guard_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status <> 'CREATED' OR NEW.version <> 0 OR NEW.provider_transfer_ref IS NOT NULL THEN
    RAISE EXCEPTION 'transfers must be inserted as CREATED' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  -- The quote must already be consumed, by the same customer (one transfer per quote is enforced
  -- by the unique quote_id constraint).
  PERFORM 1 FROM fx_quotes WHERE id = NEW.quote_id AND customer_id = NEW.customer_id AND status = 'CONSUMED';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'transfer quote not consumed by its owner' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  -- FOR SHARE blocks a concurrent soft delete until this transaction ends and, if a delete committed
  -- first, re-reads the row and sees it deleted.
  PERFORM 1 FROM beneficiaries
   WHERE id = NEW.beneficiary_id AND customer_id = NEW.customer_id AND deleted_at IS NULL
   FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'transfer beneficiary not active for its owner' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION transfers_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'transfers rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF (NEW.id, NEW.customer_id, NEW.quote_id, NEW.beneficiary_id, NEW.purpose, NEW.provider, NEW.created_at)
     IS DISTINCT FROM
     (OLD.id, OLD.customer_id, OLD.quote_id, OLD.beneficiary_id, OLD.purpose, OLD.provider, OLD.created_at) THEN
    RAISE EXCEPTION 'transfers identity fields are immutable' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF OLD.provider_transfer_ref IS NOT NULL AND NEW.provider_transfer_ref IS DISTINCT FROM OLD.provider_transfer_ref THEN
    RAISE EXCEPTION 'transfers provider reference is immutable once set' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  -- Every change is versioned so optimistic writers detect concurrent updates.
  IF NEW.version IS DISTINCT FROM OLD.version + 1 THEN
    RAISE EXCEPTION 'transfers version must advance by one' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status AND NOT transfer_transition_allowed(OLD.status, NEW.status) THEN
    RAISE EXCEPTION 'transfers status transition not allowed' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER transfers_insert_guard
  BEFORE INSERT ON transfers
  FOR EACH ROW EXECUTE FUNCTION transfers_guard_insert();

CREATE TRIGGER transfers_update_guard
  BEFORE UPDATE OR DELETE ON transfers
  FOR EACH ROW EXECUTE FUNCTION transfers_guard_update();

-- Every status a transfer enters (its initial CREATED and each later change) must be recorded by a
-- matching lifecycle event written in the same transaction. Checked at commit, so the application
-- may write the event after the row. Events written in a subtransaction (savepoint) do not count
-- (fail closed).
CREATE FUNCTION transfers_require_lifecycle_event() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  previous TEXT;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
      RETURN NULL;
    END IF;
    previous := OLD.status;
  END IF;
  PERFORM 1 FROM transaction_events
   WHERE resource_kind = 'TRANSFER' AND resource_id = NEW.id
     AND previous_status IS NOT DISTINCT FROM previous AND next_status = NEW.status
     AND xmin = pg_current_xact_id()::xid;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'transfers status change requires a lifecycle event in the same transaction'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER transfers_lifecycle_event_required
  AFTER INSERT OR UPDATE ON transfers
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION transfers_require_lifecycle_event();

-- Lifecycle events are an append-only operational history.
CREATE FUNCTION transaction_events_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'transaction_events is append-only' USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER transaction_events_immutable
  BEFORE UPDATE OR DELETE ON transaction_events
  FOR EACH ROW EXECUTE FUNCTION transaction_events_append_only();

-- History reads are ordered by creation time with a stable tie-break.
DROP INDEX IF EXISTS ix_transfers_customer_created;
CREATE INDEX ix_transfers_customer_created ON transfers (customer_id, created_at DESC, id DESC);
