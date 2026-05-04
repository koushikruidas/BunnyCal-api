ALTER TABLE calendar_event_mappings
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;

ALTER TABLE calendar_event_mappings
    DROP CONSTRAINT IF EXISTS calendar_event_mappings_status_check;

ALTER TABLE calendar_event_mappings
    ADD CONSTRAINT calendar_event_mappings_status_check
        CHECK (status IN ('CLAIMED', 'CREATED', 'FAILED', 'FAILED_PERMANENT'));

ALTER TABLE calendar_event_mappings
    DROP CONSTRAINT IF EXISTS calendar_event_mappings_status_payload_check;

ALTER TABLE calendar_event_mappings
    ADD CONSTRAINT calendar_event_mappings_status_payload_check
        CHECK (
            (status = 'CLAIMED' AND external_event_id IS NULL) OR
            (status = 'CREATED' AND external_event_id IS NOT NULL) OR
            (status = 'FAILED' AND last_error IS NOT NULL) OR
            (status = 'FAILED_PERMANENT' AND last_error IS NOT NULL)
        );

CREATE INDEX IF NOT EXISTS idx_calendar_event_mappings_failed_retry_due
    ON calendar_event_mappings (next_retry_at, updated_at)
    WHERE status = 'FAILED';

CREATE OR REPLACE FUNCTION calendar_event_mappings_before_update()
RETURNS TRIGGER AS $$
BEGIN
    -- Same-state updates are allowed, but preserve invariants.
    IF NEW.status = OLD.status THEN
        IF OLD.status = 'CREATED' AND NEW.external_event_id IS DISTINCT FROM OLD.external_event_id THEN
            RAISE EXCEPTION 'calendar_event_mappings external_event_id is immutable once CREATED';
        END IF;

        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    -- CLAIMED -> CREATED/FAILED/FAILED_PERMANENT, only by the same claim owner.
    IF OLD.status = 'CLAIMED' AND NEW.status IN ('CREATED', 'FAILED', 'FAILED_PERMANENT') THEN
        IF NEW.claimed_by IS DISTINCT FROM OLD.claimed_by THEN
            RAISE EXCEPTION 'calendar_event_mappings claim ownership violation: % -> %', OLD.claimed_by, NEW.claimed_by;
        END IF;

        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    -- FAILED -> CLAIMED (retry): clear failure residue and require new owner.
    IF OLD.status = 'FAILED' AND NEW.status = 'CLAIMED' THEN
        IF NEW.claimed_by IS NULL THEN
            RAISE EXCEPTION 'calendar_event_mappings claimed_by is required when transitioning FAILED -> CLAIMED';
        END IF;

        NEW.last_error = NULL;
        NEW.external_event_id = NULL;
        NEW.claimed_at = NOW();
        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    -- FAILED -> FAILED_PERMANENT
    IF OLD.status = 'FAILED' AND NEW.status = 'FAILED_PERMANENT' THEN
        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'calendar_event_mappings invalid transition: % -> %', OLD.status, NEW.status;
END;
$$ LANGUAGE plpgsql;
