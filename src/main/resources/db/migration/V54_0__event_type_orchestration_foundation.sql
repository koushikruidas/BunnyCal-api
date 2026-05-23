ALTER TABLE event_types
    ADD COLUMN IF NOT EXISTS organizer_calendar_connection_id UUID,
    ADD COLUMN IF NOT EXISTS availability_calendars_json TEXT;

CREATE INDEX IF NOT EXISTS idx_event_types_organizer_calendar_connection
    ON event_types (organizer_calendar_connection_id);
