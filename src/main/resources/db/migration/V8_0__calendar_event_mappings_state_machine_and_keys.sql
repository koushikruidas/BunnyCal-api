-- Harden calendar_event_mappings for explicit lifecycle intent and future multi-provider support.

-- Force explicit status assignment in writes.
ALTER TABLE calendar_event_mappings
    ALTER COLUMN status DROP DEFAULT;

-- Capture failure context for retry/reconciliation workflows.
ALTER TABLE calendar_event_mappings
    ADD COLUMN last_error TEXT;

-- Enforce provider-aware identity (future-proof for multi-provider mappings per booking).
ALTER TABLE calendar_event_mappings
    DROP CONSTRAINT calendar_event_mappings_pkey;

ALTER TABLE calendar_event_mappings
    ADD CONSTRAINT calendar_event_mappings_pkey PRIMARY KEY (booking_id, provider);

-- Status-specific payload validity:
-- CREATED must have external_event_id; CLAIMED must not.
ALTER TABLE calendar_event_mappings
    ADD CONSTRAINT calendar_event_mappings_status_payload_check
        CHECK (
            (status = 'CLAIMED' AND external_event_id IS NULL) OR
            (status = 'CREATED' AND external_event_id IS NOT NULL) OR
            (status = 'FAILED')
        );

-- Retry workers usually filter by status and sort by staleness.
CREATE INDEX idx_calendar_event_mappings_status_updated_at
    ON calendar_event_mappings (status, updated_at);

-- Guard invalid lifecycle transitions at DB level.
CREATE OR REPLACE FUNCTION calendar_event_mappings_enforce_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = OLD.status THEN
        RAISE EXCEPTION 'calendar_event_mappings invalid transition: % -> %', OLD.status, NEW.status;
    END IF;

    IF OLD.status = 'CLAIMED' AND NEW.status IN ('CREATED', 'FAILED') THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'calendar_event_mappings invalid transition: % -> %', OLD.status, NEW.status;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_calendar_event_mappings_enforce_transition
BEFORE UPDATE ON calendar_event_mappings
FOR EACH ROW
EXECUTE FUNCTION calendar_event_mappings_enforce_transition();
