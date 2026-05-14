CREATE TABLE IF NOT EXISTS sync_reconcile_decision_log (
    id UUID PRIMARY KEY,
    sync_job_id UUID NOT NULL,
    booking_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_event_id VARCHAR(255),
    input_hash VARCHAR(64) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    rationale_code VARCHAR(64) NOT NULL,
    rationale_detail TEXT,
    observed_status VARCHAR(32) NOT NULL,
    observed_error_code VARCHAR(64),
    sync_job_status VARCHAR(16) NOT NULL,
    desired_action VARCHAR(16) NOT NULL,
    projection_version BIGINT,
    terminal_intent_epoch BIGINT,
    correlation_id VARCHAR(128),
    causation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sync_reconcile_decision_log_booking_created
    ON sync_reconcile_decision_log (booking_id, created_at);

CREATE INDEX IF NOT EXISTS idx_sync_reconcile_decision_log_job_created
    ON sync_reconcile_decision_log (sync_job_id, created_at);

CREATE INDEX IF NOT EXISTS idx_sync_reconcile_decision_log_decision_created
    ON sync_reconcile_decision_log (decision, created_at);
