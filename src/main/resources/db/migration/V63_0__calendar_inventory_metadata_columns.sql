-- Forward reconciliation for calendar_connection_calendars.
--
-- Context: V62 was repaired (checksum-only) on developer databases where its DDL never
-- physically executed. Flyway therefore treats V62 as applied, but the columns it was
-- supposed to add are missing. Repair cannot create columns. This migration adds them
-- idempotently so partially-applied environments converge.
--
-- IMPORTANT: V62 is now immutable. All further inventory-metadata evolution rolls forward.

ALTER TABLE calendar_connection_calendars
    ADD COLUMN IF NOT EXISTS can_read BOOLEAN;

ALTER TABLE calendar_connection_calendars
    ADD COLUMN IF NOT EXISTS can_write BOOLEAN;

ALTER TABLE calendar_connection_calendars
    ADD COLUMN IF NOT EXISTS hidden BOOLEAN;

ALTER TABLE calendar_connection_calendars
    ADD COLUMN IF NOT EXISTS last_synced_at TIMESTAMPTZ;

-- Backfill any rows that pre-date inventory hydration with defensive defaults, so the
-- NOT NULL constraint below doesn't fail on existing data.
UPDATE calendar_connection_calendars SET can_read = TRUE  WHERE can_read  IS NULL;
UPDATE calendar_connection_calendars SET can_write = TRUE WHERE can_write IS NULL;
UPDATE calendar_connection_calendars SET hidden    = FALSE WHERE hidden   IS NULL;

ALTER TABLE calendar_connection_calendars
    ALTER COLUMN can_read SET NOT NULL,
    ALTER COLUMN can_read SET DEFAULT TRUE;

ALTER TABLE calendar_connection_calendars
    ALTER COLUMN can_write SET NOT NULL,
    ALTER COLUMN can_write SET DEFAULT TRUE;

ALTER TABLE calendar_connection_calendars
    ALTER COLUMN hidden SET NOT NULL,
    ALTER COLUMN hidden SET DEFAULT FALSE;

-- last_synced_at intentionally remains NULL-able: a null value means "never hydrated"
-- and is the trigger CalendarRuntimeStatusService uses to schedule a best-effort refresh.

-- Drop the legacy single-selection partial unique index if it is still physically present.
-- Environments where V62's DDL did execute already dropped it (DROP INDEX IF EXISTS is a
-- no-op there); environments where V62 was checksum-repaired without execution still
-- carry the index and must drop it now to allow dual-role selection going forward.
DROP INDEX IF EXISTS uk_calendar_connection_calendars_selected_per_connection;
