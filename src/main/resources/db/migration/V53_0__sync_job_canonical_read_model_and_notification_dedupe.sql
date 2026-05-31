ALTER TABLE calendar_sync_jobs
    ADD COLUMN IF NOT EXISTS scheduling_connection_id UUID,
    ADD COLUMN IF NOT EXISTS provider_event_url TEXT,
    ADD COLUMN IF NOT EXISTS conference_url TEXT,
    ADD COLUMN IF NOT EXISTS conference_provider VARCHAR(64),
    ADD COLUMN IF NOT EXISTS conference_metadata_json TEXT;

CREATE INDEX IF NOT EXISTS idx_calendar_sync_jobs_provider_external_event
    ON calendar_sync_jobs (provider, external_event_id)
    WHERE internal_ref_type = 'BOOKING' AND external_event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_calendar_sync_jobs_connection_provider_external_event
    ON calendar_sync_jobs (scheduling_connection_id, provider, external_event_id)
    WHERE internal_ref_type = 'BOOKING' AND external_event_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS booking_notification_sends (
    id UUID PRIMARY KEY,
    outbox_event_id UUID NOT NULL,
    recipient_email VARCHAR(320) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_booking_notification_sends_once
        UNIQUE (outbox_event_id, recipient_email, event_type)
);

CREATE INDEX IF NOT EXISTS idx_booking_notification_sends_event
    ON booking_notification_sends (outbox_event_id, created_at);
