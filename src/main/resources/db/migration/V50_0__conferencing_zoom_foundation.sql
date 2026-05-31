CREATE TABLE IF NOT EXISTS zoom_conferencing_connections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    refresh_token_ciphertext VARCHAR(4096) NOT NULL,
    last_token_expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_error_code VARCHAR(255),
    last_error_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_zoom_conferencing_connections_user UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS conferencing_event_mappings (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    meeting_id VARCHAR(255),
    join_url TEXT,
    host_url TEXT,
    status VARCHAR(32) NOT NULL,
    last_error VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_conferencing_event_mappings_booking_provider UNIQUE (booking_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_conferencing_event_mappings_booking
    ON conferencing_event_mappings (booking_id);
