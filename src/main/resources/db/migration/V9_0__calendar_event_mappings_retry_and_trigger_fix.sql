-- Fix lifecycle rules for retries and deterministic trigger behavior.

-- Replace payload check with stricter FAILED semantics.
ALTER TABLE calendar_event_mappings
    DROP CONSTRAINT calendar_event_mappings_status_payload_check;

ALTER TABLE calendar_event_mappings
    ADD CONSTRAINT calendar_event_mappings_status_payload_check
        CHECK (
            (status = 'CLAIMED' AND external_event_id IS NULL) OR
            (status = 'CREATED' AND external_event_id IS NOT NULL) OR
            (status = 'FAILED' AND last_error IS NOT NULL)
        );

-- Query helper for provider-scoped failed scans.
CREATE INDEX idx_calendar_event_mappings_provider_status
    ON calendar_event_mappings (provider, status);

-- Replace split triggers with one deterministic trigger.
DROP TRIGGER IF EXISTS trg_calendar_event_mappings_set_updated_at ON calendar_event_mappings;
DROP TRIGGER IF EXISTS trg_calendar_event_mappings_enforce_transition ON calendar_event_mappings;

DROP FUNCTION IF EXISTS calendar_event_mappings_set_updated_at();
DROP FUNCTION IF EXISTS calendar_event_mappings_enforce_transition();

CREATE OR REPLACE FUNCTION calendar_event_mappings_before_update()
RETURNS TRIGGER AS $$
BEGIN
    -- Same-state updates are valid (e.g. last_error/sync_token refresh).
    IF NEW.status = OLD.status THEN
        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    -- Allowed transitions:
    -- CLAIMED -> CREATED/FAILED
    -- FAILED -> CLAIMED (retry)
    IF (OLD.status = 'CLAIMED' AND NEW.status IN ('CREATED', 'FAILED'))
       OR (OLD.status = 'FAILED' AND NEW.status = 'CLAIMED') THEN
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
