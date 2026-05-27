-- Microsoft calendar-scoped delta cursors.
--
-- One row per (connection_id, external_calendar_id). The legacy
-- calendar_connections.provider_sync_cursor column remains as the
-- connection-level bookkeeping signal the scheduler uses for status
-- transitions and watchdog detection; Microsoft now stamps a deterministic
-- sentinel into it and stores real per-calendar deltaLinks here.
CREATE TABLE IF NOT EXISTS calendar_connection_sync_cursors (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES calendar_connections(id) ON DELETE CASCADE,
    external_calendar_id VARCHAR(512) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    delta_cursor TEXT,
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_calendar_connection_sync_cursors_connection_calendar
        UNIQUE (connection_id, external_calendar_id)
);

CREATE INDEX IF NOT EXISTS idx_calendar_connection_sync_cursors_connection_id
    ON calendar_connection_sync_cursors (connection_id);

-- Per-event calendar attribution. Multi-calendar sync needs to know which
-- provider calendar an ingested event came from for projection debugging,
-- per-calendar reconciliation, and future calendar-scoped availability
-- querying. Legacy events ingested before this column existed remain NULL
-- and continue to function via the connection-level path.
ALTER TABLE calendar_events
    ADD COLUMN IF NOT EXISTS external_calendar_id VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_calendar_events_connection_calendar
    ON calendar_events (connection_id, external_calendar_id);
