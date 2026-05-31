-- Full index on bookings.status.
--
-- The existing idx_bookings_status_updated is a partial compound index
-- (status, updated_at) WHERE status IN ('PENDING','CONFIRMED').
-- That partial index already makes the PENDING gauge query fast today,
-- but it does not cover terminal states (EXPIRED, CANCELLED, COMPLETED,
-- FAILED). This full index ensures any status-filtered query stays
-- efficient as the table grows.
CREATE INDEX IF NOT EXISTS idx_bookings_status
    ON bookings (status);
