ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS scheduling_provider VARCHAR(32);

COMMENT ON COLUMN bookings.scheduling_provider IS
    'Authoritative calendar provider stamped at outbox dispatch (e.g. google, microsoft). '
    'NULL for rows created before this migration — read model falls back to latest-job ordering.';

CREATE INDEX IF NOT EXISTS idx_bookings_scheduling_provider
    ON bookings (scheduling_provider)
    WHERE scheduling_provider IS NOT NULL;
