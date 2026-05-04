CREATE TABLE calendar_provider_operations (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    connection_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    external_event_id VARCHAR(255),
    last_error VARCHAR(500),
    last_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_calendar_provider_op_idempotency UNIQUE (provider, idempotency_key)
);

CREATE INDEX idx_calendar_provider_op_status_updated
    ON calendar_provider_operations (status, updated_at);
