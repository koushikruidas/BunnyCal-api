-- session_registrations: per-attendee enrollment for a GROUP event session.
--
-- Design notes:
--   * host_id is denormalized from event_sessions to enable index-only joins and
--     partition-local queries. It is the host_id of the parent session.
--
--   * confirmed_count on event_sessions is NOT incremented for PENDING registrations —
--     only for CONFIRMED ones. PENDING registrations hold a seat reservation but do not
--     consume capacity until confirmed. This mirrors how PENDING bookings work in the
--     ONE_ON_ONE model.
--
--   * The active-email partial unique index enforces one active registration per
--     (session, email) pair. Cancelled registrations are excluded, which allows an
--     attendee to re-register after cancelling.
--
--   * capability tokens (booking_action_tokens) are reused for registration management.
--     Tokens are bound to (session_id, host_id) passed as (bookingId, bookingHostId)
--     in the existing token table — no FK constraints on that table, so this is safe.

CREATE TABLE session_registrations (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    session_id  UUID         NOT NULL,
    host_id     UUID         NOT NULL,
    guest_email VARCHAR(255) NOT NULL,
    guest_name  VARCHAR(120),
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    expires_at  TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT session_registrations_pkey
        PRIMARY KEY (id),

    CONSTRAINT session_registrations_status_check
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED'))
);

-- One active registration per email per session.
-- WHERE status != 'CANCELLED' allows re-registration after cancellation.
CREATE UNIQUE INDEX idx_session_registrations_active_email
    ON session_registrations (session_id, guest_email)
    WHERE status != 'CANCELLED';

-- Primary join path: load all registrations for a session.
CREATE INDEX idx_session_registrations_session
    ON session_registrations (session_id, host_id);

-- Expiry reaper: find PENDING registrations past their hold window.
CREATE INDEX idx_session_registrations_pending_expiry
    ON session_registrations (expires_at)
    WHERE status = 'PENDING' AND expires_at IS NOT NULL;

CREATE OR REPLACE FUNCTION session_registrations_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_session_registrations_updated_at
BEFORE UPDATE ON session_registrations
FOR EACH ROW EXECUTE FUNCTION session_registrations_set_updated_at();
