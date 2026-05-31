ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS terminal_intent_epoch BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS provider_event_projections (
    id UUID PRIMARY KEY,
    booking_id UUID,
    connection_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_event_id VARCHAR(255) NOT NULL,
    projection_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    projection_version BIGINT NOT NULL DEFAULT 0,
    provider_sequence BIGINT,
    provider_updated_at TIMESTAMPTZ,
    provider_etag VARCHAR(255),
    payload_hash VARCHAR(128),
    last_observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_provider_event_projection_key UNIQUE (connection_id, provider, external_event_id),
    CONSTRAINT ck_provider_event_projection_status CHECK (projection_status IN ('ACTIVE', 'TOMBSTONED_SOFT', 'TOMBSTONED_HARD'))
);

CREATE INDEX IF NOT EXISTS idx_provider_event_projection_booking
    ON provider_event_projections (booking_id);

CREATE INDEX IF NOT EXISTS idx_provider_event_projection_observed
    ON provider_event_projections (provider, last_observed_at);

ALTER TABLE calendar_webhook_events
    ADD COLUMN IF NOT EXISTS source_connection_id UUID,
    ADD COLUMN IF NOT EXISTS delivery_key VARCHAR(255);

UPDATE calendar_webhook_events
SET delivery_key = CONCAT(provider, ':', provider_event_id)
WHERE delivery_key IS NULL;

ALTER TABLE calendar_webhook_events
    ALTER COLUMN delivery_key SET NOT NULL;

ALTER TABLE calendar_webhook_events
    DROP CONSTRAINT IF EXISTS uq_calendar_webhook_events_provider_event;

ALTER TABLE calendar_webhook_events
    ADD CONSTRAINT uq_calendar_webhook_events_delivery_key UNIQUE (delivery_key);
