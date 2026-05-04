-- Retry hardening + operational visibility for calendar_event_mappings.

ALTER TABLE calendar_event_mappings
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN claimed_by VARCHAR(100);

-- Optimize sparse retry worker scan:
--   WHERE status = 'FAILED' ORDER BY updated_at LIMIT N
CREATE INDEX idx_calendar_event_mappings_failed_retry
    ON calendar_event_mappings (updated_at)
    WHERE status = 'FAILED';

-- Replace v9 trigger with retry-cleanup semantics and claim metadata handling.
DROP TRIGGER IF EXISTS trg_calendar_event_mappings_before_update ON calendar_event_mappings;
DROP FUNCTION IF EXISTS calendar_event_mappings_before_update();

CREATE OR REPLACE FUNCTION calendar_event_mappings_before_update()
RETURNS TRIGGER AS $$
BEGIN
    -- Same-state updates are valid (e.g. last_error/sync_token refresh).
    IF NEW.status = OLD.status THEN
        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    -- CLAIMED -> CREATED/FAILED
    IF OLD.status = 'CLAIMED' AND NEW.status IN ('CREATED', 'FAILED') THEN
        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    -- FAILED -> CLAIMED (retry): clear failure residue and re-claim.
    IF OLD.status = 'FAILED' AND NEW.status = 'CLAIMED' THEN
        NEW.last_error = NULL;
        NEW.external_event_id = NULL;
        NEW.claimed_at = NOW();
        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'calendar_event_mappings invalid transition: % -> %', OLD.status, NEW.status;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_calendar_event_mappings_before_update
BEFORE UPDATE ON calendar_event_mappings
FOR EACH ROW
EXECUTE FUNCTION calendar_event_mappings_before_update();
