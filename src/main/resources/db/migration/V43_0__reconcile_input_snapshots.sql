CREATE TABLE IF NOT EXISTS sync_reconcile_input_snapshots (
    id UUID PRIMARY KEY,
    snapshot_version BIGSERIAL NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    sync_job_id UUID NOT NULL,
    booking_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_event_id VARCHAR(255),
    booking_state VARCHAR(16) NOT NULL,
    sync_status VARCHAR(16) NOT NULL,
    projection_lifecycle VARCHAR(24) NOT NULL,
    participation_lifecycle VARCHAR(24) NOT NULL,
    invariant_classification VARCHAR(32) NOT NULL,
    desired_action VARCHAR(16) NOT NULL,
    observed_status VARCHAR(32) NOT NULL,
    observed_error_code VARCHAR(64),
    projection_version BIGINT,
    terminal_intent_epoch BIGINT,
    projection_connection_id UUID,
    provider_updated_at TIMESTAMPTZ,
    provider_etag VARCHAR(255),
    provider_sequence BIGINT,
    recurring_hint BOOLEAN NOT NULL DEFAULT FALSE,
    correlation_id VARCHAR(128),
    causation_id VARCHAR(128),
    lineage_source VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sync_reconcile_input_snapshots_version
    ON sync_reconcile_input_snapshots (snapshot_version);

CREATE INDEX IF NOT EXISTS idx_sync_reconcile_input_snapshots_job_created
    ON sync_reconcile_input_snapshots (sync_job_id, created_at);

CREATE INDEX IF NOT EXISTS idx_sync_reconcile_input_snapshots_booking_created
    ON sync_reconcile_input_snapshots (booking_id, created_at);
