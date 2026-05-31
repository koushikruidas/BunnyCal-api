CREATE UNIQUE INDEX IF NOT EXISTS ux_calendar_user_provider
    ON calendar_connections(user_id, provider);

ALTER TABLE calendar_connections
    RENAME COLUMN expires_at TO last_token_expires_at;

ALTER TABLE calendar_connections
    ALTER COLUMN scopes TYPE TEXT[]
    USING string_to_array(scopes, ' ');

ALTER TABLE calendar_connections
    ADD COLUMN IF NOT EXISTS last_error_code TEXT,
    ADD COLUMN IF NOT EXISTS last_error_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_synced_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_calendar_connections_scopes_gin
    ON calendar_connections USING GIN (scopes);
