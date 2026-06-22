-- Group Event Reservation Windows
--
-- A GROUP event type may reserve recurring weekly windows (e.g. every Wednesday
-- 09:00-11:00). These windows act as busy blocks for ALL OTHER event types owned
-- by the same host, while remaining visible to the owning event type itself.
--
-- Host availability stays global (availability_rules unchanged); this table is
-- additive and event-type scoped. Reservation is computed from these rows at
-- slot-generation time -- no sessions are materialized to reserve time.
CREATE TABLE IF NOT EXISTS group_event_reservation_windows (
    id UUID PRIMARY KEY,
    event_type_id UUID NOT NULL,
    day_of_week VARCHAR(10) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_group_event_reservation_windows_event_type
        FOREIGN KEY (event_type_id) REFERENCES event_types(id) ON DELETE CASCADE,
    CONSTRAINT ck_group_event_reservation_windows_day_of_week
        CHECK (day_of_week IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    CONSTRAINT ck_group_event_reservation_windows_time_order
        CHECK (start_time < end_time)
);

-- Slot generation loads, per host + day-of-week, the reservation windows owned by
-- OTHER event types. Index supports that lookup path.
CREATE INDEX IF NOT EXISTS idx_group_event_reservation_windows_event_type
    ON group_event_reservation_windows (event_type_id);

CREATE INDEX IF NOT EXISTS idx_group_event_reservation_windows_event_type_day
    ON group_event_reservation_windows (event_type_id, day_of_week);
