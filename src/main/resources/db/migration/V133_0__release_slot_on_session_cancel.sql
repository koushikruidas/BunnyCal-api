-- A Cancelled Session Stops Owning Its Slot Key
--
-- event_sessions_unique_slot was UNIQUE (host_id, event_type_id, start_time) with no status
-- predicate, so a CANCELLED row held its exact start time forever. A host who cancelled a class
-- and later wanted to run a session at that time again could not: the write failed on a row for
-- a meeting that is not happening.
--
-- Whether that time is open is the host's call, and the constraint was overriding it. Uniqueness
-- that matters is preserved -- at most one LIVE session per (host, event type, start) -- while
-- terminal rows keep their history without keeping their claim.
--
-- This is deliberately NOT a statement that cancelled slots are open to everyone. A guest must
-- not reopen a class the host called off, and does not: moveRegistration and joinSession resolve
-- the slot through findOrCreateSession, which still finds the cancelled row and rejects with
-- SESSION_CANCELLED. That guard is in code because it is about who is asking, which the schema
-- cannot express. GuestSessionRescheduleIT.movingToACancelledSession_isRejected pins it.
--
-- COMPLETED is included for the same reason: a finished class does not reserve its hour against
-- a future one.
--
-- Concurrent joins are serialized by the advisory lock in joinSession, so this index is a
-- backstop against a lost race rather than the primary guard; narrowing it does not widen that
-- window.

ALTER TABLE event_sessions
    DROP CONSTRAINT IF EXISTS event_sessions_unique_slot;

CREATE UNIQUE INDEX IF NOT EXISTS event_sessions_unique_live_slot
    ON event_sessions (host_id, event_type_id, start_time)
    WHERE status NOT IN ('CANCELLED', 'COMPLETED');
