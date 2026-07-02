ALTER TABLE users
    ADD COLUMN IF NOT EXISTS deletion_requested_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS account_deletion_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    last_error_message VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_account_deletion_jobs_user UNIQUE (user_id)
);

CREATE INDEX IF NOT EXISTS idx_account_deletion_jobs_status_next_attempt
    ON account_deletion_jobs (status, next_attempt_at);

CREATE TABLE IF NOT EXISTS deleted_account_tombstones (
    id UUID PRIMARY KEY,
    normalized_email VARCHAR(255),
    provider VARCHAR(32),
    provider_user_id VARCHAR(255),
    deleted_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_deleted_account_tombstones_email
    ON deleted_account_tombstones (normalized_email)
    WHERE normalized_email IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_deleted_account_tombstones_provider_subject
    ON deleted_account_tombstones (provider, provider_user_id)
    WHERE provider IS NOT NULL AND provider_user_id IS NOT NULL;
