CREATE TABLE calendar_webhook_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    payload_hash VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    error VARCHAR(500),
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_calendar_webhook_events_provider_event UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_calendar_webhook_events_received_at
    ON calendar_webhook_events (received_at);

CREATE INDEX idx_calendar_webhook_events_status_received
    ON calendar_webhook_events (status, received_at);
