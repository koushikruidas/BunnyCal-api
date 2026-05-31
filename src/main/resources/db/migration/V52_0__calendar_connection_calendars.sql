CREATE TABLE IF NOT EXISTS calendar_connection_calendars (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES calendar_connections(id) ON DELETE CASCADE,
    external_calendar_id VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    is_selected BOOLEAN NOT NULL DEFAULT FALSE,
    sync_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_calendar_connection_calendars_connection_external UNIQUE (connection_id, external_calendar_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_calendar_connection_calendars_selected_per_connection
    ON calendar_connection_calendars(connection_id)
    WHERE is_selected = TRUE;
