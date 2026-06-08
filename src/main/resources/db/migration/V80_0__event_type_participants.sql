-- Phase 2: Event Type Participants
-- Multi-host assignment surface for ROUND_ROBIN and COLLECTIVE event types.
-- Stores user_id directly — scheduling/availability/calendar/booking all operate on
-- user_id, so this table introduces NO team_member dependency into the engine.
--
-- Backward compatibility:
--   ONE_ON_ONE / GROUP event types may have zero rows here; callers fall back to
--   event_types.user_id (the owner) as the implicit single participant. No backfill
--   of existing event types is required.

CREATE TABLE event_type_participants (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type_id   UUID        NOT NULL REFERENCES event_types(id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    display_order   INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(event_type_id, user_id)
);

CREATE INDEX idx_event_type_participants_event ON event_type_participants(event_type_id);
CREATE INDEX idx_event_type_participants_user  ON event_type_participants(user_id);
