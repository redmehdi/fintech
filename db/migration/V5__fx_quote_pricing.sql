-- FX-007: pricing module. SIMULATION ONLY.
-- A quote is an immutable pricing record: once issued, no pricing input, amount, expiry or owner
-- may change and the row may not be deleted. The only permitted change is the status transition
-- ISSUED -> CONSUMED (transfer creation, FX-008) or ISSUED -> EXPIRED.
--
-- No application path wrote fx_quotes before V5, so any pre-existing row was not priced by this
-- module. Such rows are retired (ISSUED -> EXPIRED) so they can never be consumed, and the new
-- checks are added NOT VALID: they apply to every row inserted or updated from now on, while
-- legacy rows (now frozen by the trigger below) are left as they were for traceability.

UPDATE fx_quotes SET status = 'EXPIRED' WHERE status = 'ISSUED';

-- Only the deterministic simulator may price quotes. Accepting a partner rate source requires a
-- reviewed migration together with the legal/partner milestone (fail closed).
ALTER TABLE fx_quotes
  ADD CONSTRAINT fx_quotes_simulated_rate_source
    CHECK (rate_source = 'SIMULATED_ILLUSTRATIVE') NOT VALID;

-- A quote expires after it was issued and within a bounded window.
ALTER TABLE fx_quotes
  ADD CONSTRAINT fx_quotes_expiry_window
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '30 minutes') NOT VALID;

-- Fee breakdown (PRD §7): the fixed component of fee_source_minor, in source minor units, frozen at
-- issue so the variable component (fee_source_minor - fee_fixed_minor) stays derivable after a tariff
-- change. Nullable only for legacy rows, which carry no breakdown; every new row must provide it.
ALTER TABLE fx_quotes ADD COLUMN fee_fixed_minor BIGINT;
ALTER TABLE fx_quotes
  ADD CONSTRAINT fx_quotes_fee_breakdown
    CHECK (fee_fixed_minor IS NOT NULL AND fee_fixed_minor >= 0 AND fee_fixed_minor <= fee_source_minor) NOT VALID;

CREATE FUNCTION fx_quotes_guard_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'fx_quotes rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF (NEW.id, NEW.customer_id, NEW.quote_type, NEW.source_currency, NEW.target_currency,
      NEW.source_amount_minor, NEW.fee_source_minor, NEW.fee_fixed_minor, NEW.total_debit_minor, NEW.target_amount_minor,
      NEW.rate, NEW.rate_source, NEW.mode, NEW.expires_at, NEW.created_at)
     IS DISTINCT FROM
     (OLD.id, OLD.customer_id, OLD.quote_type, OLD.source_currency, OLD.target_currency,
      OLD.source_amount_minor, OLD.fee_source_minor, OLD.fee_fixed_minor, OLD.total_debit_minor, OLD.target_amount_minor,
      OLD.rate, OLD.rate_source, OLD.mode, OLD.expires_at, OLD.created_at) THEN
    RAISE EXCEPTION 'fx_quotes pricing fields are immutable' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status
     AND NOT (OLD.status = 'ISSUED' AND NEW.status IN ('CONSUMED', 'EXPIRED')) THEN
    RAISE EXCEPTION 'fx_quotes status transition not allowed' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  -- A quote can only be consumed strictly before its expiry, judged by the database's wall clock at
  -- the moment of the update (clock_timestamp(), not the transaction start now()), so a freshness
  -- check that passed just before the deadline cannot be followed by a consumption after it.
  IF NEW.status = 'CONSUMED' AND OLD.status = 'ISSUED' AND clock_timestamp() >= OLD.expires_at THEN
    RAISE EXCEPTION 'fx_quotes quote expired' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER fx_quotes_immutable
  BEFORE UPDATE OR DELETE ON fx_quotes
  FOR EACH ROW EXECUTE FUNCTION fx_quotes_guard_immutable();
