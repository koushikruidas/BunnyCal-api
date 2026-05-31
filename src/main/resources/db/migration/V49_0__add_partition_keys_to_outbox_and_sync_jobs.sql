ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS partition_key UUID;

ALTER TABLE calendar_sync_jobs
    ADD COLUMN IF NOT EXISTS partition_key UUID;

CREATE INDEX IF NOT EXISTS idx_outbox_events_partition_key
    ON outbox_events (partition_key)
    WHERE partition_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_calendar_sync_jobs_partition_key
    ON calendar_sync_jobs (partition_key)
    WHERE partition_key IS NOT NULL;
