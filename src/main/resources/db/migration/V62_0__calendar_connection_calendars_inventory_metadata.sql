ALTER TABLE calendar_connection_calendars
    ADD COLUMN IF NOT EXISTS can_read BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS can_write BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_synced_at TIMESTAMPTZ;

-- The dual-selection model (availability vs. projection) means a single connection can
-- have at most one "default" calendar per role. The legacy uk_..._selected_per_connection
-- index forbade more than one selected row per connection, which collides with the new
-- design. Selection truth now derives from event_types (availability_calendars_json and
-- projection_connection_id+projection_calendar_id) rather than from is_selected on the
-- inventory row, so the unique constraint is no longer load-bearing.
DROP INDEX IF EXISTS uk_calendar_connection_calendars_selected_per_connection;
