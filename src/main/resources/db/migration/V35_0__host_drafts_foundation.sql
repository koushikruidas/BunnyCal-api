CREATE TABLE IF NOT EXISTS host_drafts (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    timezone VARCHAR(50) NOT NULL,
    public_slug VARCHAR(120) NOT NULL UNIQUE,
    event_name VARCHAR(120) NOT NULL,
    duration_minutes INTEGER NOT NULL,
    config_json TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    claimed_user_id UUID NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_host_drafts_duration_positive CHECK (duration_minutes > 0)
);

CREATE INDEX IF NOT EXISTS idx_host_drafts_expires_at
    ON host_drafts (expires_at);
