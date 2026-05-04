CREATE TABLE calendar_sync_jobs (
    id UUID PRIMARY KEY,
    internal_ref_type VARCHAR(32) NOT NULL,
    internal_ref_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    desired_action VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    external_event_id VARCHAR(255),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT calendar_sync_jobs_desired_action_check
        CHECK (desired_action IN ('CREATE', 'UPDATE', 'DELETE')),
    CONSTRAINT calendar_sync_jobs_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'SYNCED', 'FAILED')),
    CONSTRAINT calendar_sync_jobs_unique_ref_provider
        UNIQUE (internal_ref_type, internal_ref_id, provider)
);

CREATE INDEX idx_calendar_sync_jobs_pickup
    ON calendar_sync_jobs (status, next_retry_at, updated_at);

CREATE OR REPLACE FUNCTION set_calendar_sync_jobs_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_calendar_sync_jobs_updated_at ON calendar_sync_jobs;
CREATE TRIGGER trg_calendar_sync_jobs_updated_at
BEFORE UPDATE ON calendar_sync_jobs
FOR EACH ROW
EXECUTE FUNCTION set_calendar_sync_jobs_updated_at();
