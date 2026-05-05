-- HELD currently maps to PENDING in persisted state model.
CREATE UNIQUE INDEX IF NOT EXISTS ux_booking_conflict
    ON bookings (host_id, start_time, end_time)
    WHERE status IN ('PENDING', 'CONFIRMED');
