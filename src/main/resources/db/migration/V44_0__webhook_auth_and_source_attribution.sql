ALTER TABLE calendar_webhook_replay_fixtures
    ADD COLUMN IF NOT EXISTS delivery_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS source_attribution VARCHAR(32) NOT NULL DEFAULT 'WEBHOOK';

ALTER TABLE calendar_webhook_events
    ADD COLUMN IF NOT EXISTS source_attribution VARCHAR(32) NOT NULL DEFAULT 'WEBHOOK';

ALTER TABLE calendar_webhook_events
    ADD COLUMN IF NOT EXISTS provider_updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS provider_etag VARCHAR(255),
    ADD COLUMN IF NOT EXISTS provider_sequence BIGINT;

ALTER TABLE calendar_webhook_events
    ADD CONSTRAINT ck_calendar_webhook_events_source_attribution
        CHECK (source_attribution IN ('PULL_SYNC', 'WEBHOOK', 'USER_ACTION', 'RECONCILIATION'));

ALTER TABLE calendar_webhook_replay_fixtures
    ADD CONSTRAINT ck_calendar_webhook_replay_fixtures_source_attribution
        CHECK (source_attribution IN ('PULL_SYNC', 'WEBHOOK', 'USER_ACTION', 'RECONCILIATION'));
