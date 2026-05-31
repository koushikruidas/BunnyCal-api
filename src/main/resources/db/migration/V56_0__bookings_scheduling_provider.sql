ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS scheduling_provider VARCHAR(32);

COMMENT ON COLUMN bookings.scheduling_provider IS
    'Calendar provider used for sync job dispatch (e.g. google, microsoft). Stamped at outbox dispatch time. NULL for bookings created before this migration.';

CREATE INDEX IF NOT EXISTS idx_bookings_scheduling_provider
    ON bookings (host_id, scheduling_provider)
    WHERE scheduling_provider IS NOT NULL;
