ALTER TABLE calendar_events
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill safety for environments that may have pre-existing nullable rows from manual drift.
UPDATE calendar_events
SET deleted = FALSE
WHERE deleted IS NULL;
