CREATE TABLE calendar_connections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    refresh_token_ciphertext VARCHAR(4096) NOT NULL,
    access_token VARCHAR(4096),
    expires_at TIMESTAMPTZ NOT NULL,
    scopes VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_calendar_connections_user_provider UNIQUE (user_id, provider)
);

CREATE TABLE calendar_external_event_mappings (
    id UUID PRIMARY KEY,
    internal_ref_type VARCHAR(32) NOT NULL,
    internal_ref_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_event_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_calendar_event_mapping_ref_provider
        UNIQUE (internal_ref_type, internal_ref_id, provider)
);

CREATE INDEX idx_calendar_connections_provider_status
    ON calendar_connections (provider, status);

CREATE INDEX idx_calendar_external_event_mappings_provider_status
    ON calendar_external_event_mappings (provider, status);
