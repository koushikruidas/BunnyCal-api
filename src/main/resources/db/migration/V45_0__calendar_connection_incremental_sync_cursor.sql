ALTER TABLE calendar_connections
    ADD COLUMN IF NOT EXISTS provider_sync_cursor TEXT,
    ADD COLUMN IF NOT EXISTS provider_cursor_updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS provider_cursor_invalidated_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_calendar_connections_cursor_updated
    ON calendar_connections (provider_cursor_updated_at);
