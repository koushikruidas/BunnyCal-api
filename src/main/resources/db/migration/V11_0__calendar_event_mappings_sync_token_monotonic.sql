-- Prevent stale retries from regressing sync_token.

DROP TRIGGER IF EXISTS trg_calendar_event_mappings_before_update ON calendar_event_mappings;
DROP FUNCTION IF EXISTS calendar_event_mappings_before_update();

CREATE OR REPLACE FUNCTION calendar_event_mappings_before_update()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.sync_token < OLD.sync_token THEN
        RAISE EXCEPTION 'calendar_event_mappings sync_token regression: % -> %', OLD.sync_token, NEW.sync_token;
    END IF;

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
