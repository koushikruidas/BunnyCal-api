ALTER TABLE outbox_events
    ALTER COLUMN next_attempt_at DROP NOT NULL;

ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS outbox_events_status_check;

ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_status_check
        CHECK (status IN ('PENDING','PROCESSING','RETRYING','PROCESSED','FAILED'));
