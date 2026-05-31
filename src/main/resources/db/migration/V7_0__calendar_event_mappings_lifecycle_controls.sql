-- Upgrade calendar_event_mappings from passive mapping to lifecycle control table.

-- `external_event_id` is unknown at claim time and only populated after provider create succeeds.
ALTER TABLE calendar_event_mappings
    ALTER COLUMN external_event_id DROP NOT NULL;

-- `sync_token` must be explicitly assigned by caller; remove ambiguous default.
ALTER TABLE calendar_event_mappings
    ALTER COLUMN sync_token DROP DEFAULT;

ALTER TABLE calendar_event_mappings
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CLAIMED',
    ADD CONSTRAINT calendar_event_mappings_status_check
        CHECK (status IN ('CLAIMED', 'CREATED', 'FAILED'));

CREATE INDEX idx_calendar_event_mappings_status
    ON calendar_event_mappings (status);

-- Keep updated_at trustworthy at DB level.
CREATE OR REPLACE FUNCTION calendar_event_mappings_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_calendar_event_mappings_set_updated_at
BEFORE UPDATE ON calendar_event_mappings
FOR EACH ROW
EXECUTE FUNCTION calendar_event_mappings_set_updated_at();
