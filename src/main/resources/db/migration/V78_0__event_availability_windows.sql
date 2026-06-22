-- Event Availability Filter Windows
--
-- A demand-driven event type (ONE_ON_ONE, ROUND_ROBIN, COLLECTIVE) may define
-- recurring weekly windows that act ONLY as a FILTER on the host's availability for
-- that specific event type. They are NOT reservations:
--   * they never block any other event type;
--   * they never reserve time;
--   * they create no ownership.
--
-- Effective availability for a demand-driven event type =
--     host availability (availability_rules)
--   ∩ event availability filter (this table, if any rows exist for the type)
--   ∩ free/busy.
--
-- Distinction from neighbouring tables:
--   * availability_rules            -> host-global working hours (keyed by user_id).
--   * group_event_reservation_windows -> GROUP ownership; blocks OTHER event types.
--   * event_availability_windows    -> per-event FILTER; blocks nothing, owns nothing.
--
-- Semantics when NO rows exist for an event type: no filtering -- the event sees the
-- host's full availability (back-compatible default for every existing event type).
CREATE TABLE IF NOT EXISTS event_availability_windows (
    id UUID PRIMARY KEY,
    event_type_id UUID NOT NULL,
    day_of_week VARCHAR(10) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_event_availability_windows_event_type
        FOREIGN KEY (event_type_id) REFERENCES event_types(id) ON DELETE CASCADE,
    CONSTRAINT ck_event_availability_windows_day_of_week
        CHECK (day_of_week IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    CONSTRAINT ck_event_availability_windows_time_order
        CHECK (start_time < end_time)
);

-- Slot generation loads, per event type + day-of-week, this event's own filter
-- windows. Index supports that lookup path.
CREATE INDEX IF NOT EXISTS idx_event_availability_windows_event_type
    ON event_availability_windows (event_type_id);

CREATE INDEX IF NOT EXISTS idx_event_availability_windows_event_type_day
    ON event_availability_windows (event_type_id, day_of_week);
