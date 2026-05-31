-- Phase 2 (F7 + F8): persistence-backed retry state for calendar_connections.
--
-- failure_count       — count of consecutive non-success transitions (markFailure increments,
--                       markActive resets). Used to drive exponential backoff and the F8
--                       max-failures-then-quarantine threshold.
-- next_retry_at       — earliest time the scheduler is allowed to attempt this connection
--                       again. NULL means "no scheduled wait" (ACTIVE/SYNCING/PENDING).
-- quarantined_until   — set when a connection is parked in REVOKED for transient overflow;
--                       lets operators see when the quarantine entered effect. NULL when
--                       the row is healthy or was REVOKED via explicit disconnect.

ALTER TABLE calendar_connections
    ADD COLUMN IF NOT EXISTS failure_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS quarantined_until TIMESTAMPTZ;

-- Drives the scheduler's "due" query:
--   WHERE status IN ('ACTIVE','SYNCING') OR (status IN ('FAILED','ERROR') AND next_retry_at <= now())
-- Partial because next_retry_at is null for the healthy steady state.
CREATE INDEX IF NOT EXISTS idx_calendar_connections_status_next_retry_at
    ON calendar_connections (status, next_retry_at)
    WHERE next_retry_at IS NOT NULL;
