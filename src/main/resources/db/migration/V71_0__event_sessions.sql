-- event_sessions: the shared time block for a GROUP (or future ROUND_ROBIN/COLLECTIVE) event.
--
-- Design notes:
--   * host_id is NOT NULL for GROUP and ROUND_ROBIN. For COLLECTIVE (future), a
--     migration will DROP NOT NULL and introduce a session_hosts child table.
--     That ALTER is metadata-only in PG 11+ (no row rewrite).
--
--   * confirmed_count is a denormalized counter of CONFIRMED registrations.
--     It is maintained by CAS updates in SessionService under a per-slot advisory lock.
--     The DB constraint (confirmed_count BETWEEN 0 AND capacity) is a hard guard.
--     A reconciliation job corrects any drift.
--
--   * UNIQUE (host_id, event_type_id, start_time) is a safety net independent of the
--     advisory lock. If two transactions race past the advisory lock (should be impossible
--     under normal operation), only one INSERT succeeds; the other catches 23505 and
--     re-selects the existing row.
--
--   * calendar_sequence and terminal_intent_epoch mirror the same fields on bookings and
--     drive the SessionSyncReconciler in the same way BookingSyncReconciler uses them.

CREATE TABLE event_sessions (
    id                    UUID        NOT NULL DEFAULT gen_random_uuid(),
    host_id               UUID        NOT NULL,
    event_type_id         UUID        NOT NULL,
    start_time            TIMESTAMPTZ NOT NULL,
    end_time              TIMESTAMPTZ NOT NULL,
    status                VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    capacity              INT         NOT NULL,
    confirmed_count       INT         NOT NULL DEFAULT 0,
    version               BIGINT      NOT NULL DEFAULT 0,
    calendar_sequence     BIGINT      NOT NULL DEFAULT 0,
    terminal_intent_epoch BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT event_sessions_pkey
        PRIMARY KEY (id),

    CONSTRAINT event_sessions_status_check
        CHECK (status IN ('OPEN', 'FULL', 'CANCELLED', 'COMPLETED')),

    CONSTRAINT event_sessions_time_order
        CHECK (start_time < end_time),

    CONSTRAINT event_sessions_count_sane
        CHECK (confirmed_count >= 0 AND confirmed_count <= capacity),

    CONSTRAINT event_sessions_unique_slot
        UNIQUE (host_id, event_type_id, start_time)
);

-- Hot read path: slot engine and dashboard queries filter by host + time window.
CREATE INDEX idx_event_sessions_host_start
    ON event_sessions (host_id, start_time);

-- Event-type scoped queries (e.g. "is this event type still in use?").
CREATE INDEX idx_event_sessions_event_type
    ON event_sessions (event_type_id);

-- Slot engine filters on status='FULL' to exclude fully booked slots.
CREATE INDEX idx_event_sessions_status_start
    ON event_sessions (status, start_time)
    WHERE status IN ('OPEN', 'FULL');

CREATE OR REPLACE FUNCTION event_sessions_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_sessions_updated_at
BEFORE UPDATE ON event_sessions
FOR EACH ROW EXECUTE FUNCTION event_sessions_set_updated_at();
