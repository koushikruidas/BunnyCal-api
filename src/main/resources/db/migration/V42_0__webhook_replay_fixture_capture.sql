CREATE TABLE IF NOT EXISTS calendar_webhook_replay_fixtures (
    id UUID PRIMARY KEY,
    arrival_index BIGSERIAL NOT NULL,
    provider VARCHAR(32) NOT NULL,
    connection_id UUID NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    delivery_key VARCHAR(255) NOT NULL,
    payload_hash VARCHAR(128),
    raw_payload TEXT,
    dedup_result VARCHAR(16) NOT NULL,
    provider_updated_at TIMESTAMPTZ,
    provider_etag VARCHAR(255),
    provider_sequence BIGINT,
    recurring_hint BOOLEAN NOT NULL DEFAULT FALSE,
    correlation_id VARCHAR(128),
    causation_id VARCHAR(128),
    captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_calendar_webhook_replay_fixtures_dedup_result
        CHECK (dedup_result IN ('FIRST_SEEN', 'DUPLICATE'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_calendar_webhook_replay_fixtures_arrival_index
    ON calendar_webhook_replay_fixtures (arrival_index);

CREATE INDEX IF NOT EXISTS idx_calendar_webhook_replay_fixtures_conn_arrival
    ON calendar_webhook_replay_fixtures (connection_id, arrival_index);

CREATE INDEX IF NOT EXISTS idx_calendar_webhook_replay_fixtures_provider_captured
    ON calendar_webhook_replay_fixtures (provider, captured_at);
