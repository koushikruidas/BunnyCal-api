ALTER TABLE calendar_connections
    ADD COLUMN IF NOT EXISTS webhook_channel_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS webhook_resource_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS webhook_channel_expires_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uk_calendar_connections_webhook_channel
    ON calendar_connections (webhook_channel_id)
    WHERE webhook_channel_id IS NOT NULL;
