-- =====================================================================
-- Transactional Outbox.
--
-- Two tables:
--   outbox_events   — durable event queue; written in the same TX as the
--                     booking row so events can never be lost even if the
--                     worker crashes.
--   processed_events — Option-A idempotency guard; one row per event that
--                      was successfully dispatched downstream. Prevents a
--                      worker from re-dispatching after a crash-and-recover
--                      cycle where the outbox row was never updated to
--                      PROCESSED.
-- =====================================================================

CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT outbox_events_status_check
        CHECK (status IN ('PENDING','PROCESSING','PROCESSED','FAILED'))
);

-- Worker polling: only PENDING rows, ordered by creation time to preserve
-- rough FIFO ordering. Partial index keeps it tiny as PROCESSED/FAILED rows
-- accumulate.
CREATE INDEX idx_outbox_pending
    ON outbox_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';

-- Reaper: find PROCESSING rows whose worker died.
CREATE INDEX idx_outbox_processing
    ON outbox_events (updated_at)
    WHERE status = 'PROCESSING';

-- DB-owned updated_at — the reaper uses this column for its timeout check.
CREATE OR REPLACE FUNCTION outbox_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_outbox_set_updated_at
BEFORE UPDATE ON outbox_events
FOR EACH ROW
EXECUTE FUNCTION outbox_set_updated_at();

-- ---------------------------------------------------------------------
-- processed_events: Option-A idempotency guard.
--
-- When a worker successfully dispatches an event it inserts a row here
-- (inside the same TX as the outbox UPDATE to PROCESSED). If the worker
-- crashes after dispatch but before committing, the TX rolls back and this
-- table stays empty for that event_id. On the next attempt the INSERT
-- succeeds again, indicating a genuine first dispatch.
--
-- If somehow a worker crashes after commit (event is PROCESSED in outbox
-- but the worker dies before acknowledging), the row already exists here.
-- A future worker loading the stuck PROCESSING row via the reaper will
-- find the row here, skip dispatch, and mark the outbox PROCESSED.
-- ---------------------------------------------------------------------
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
