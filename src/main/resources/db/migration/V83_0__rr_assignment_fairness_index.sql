-- Partial index to support the RR assignment fairness query that filters
-- bookings to CONFIRMED/COMPLETED status only. Excludes CANCELLED, EXPIRED,
-- PENDING bookings from the LRA (Least Recently Assigned) ordering.
CREATE INDEX idx_bookings_event_type_confirmed
    ON bookings(event_type_id, status)
    WHERE status IN ('CONFIRMED', 'COMPLETED');
