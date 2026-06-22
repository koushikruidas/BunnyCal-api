-- Collective event type: per-participant hold table for DB-enforced slot exclusivity.
-- btree_gist is already enabled (V3_0__bookings.sql).
--
-- released_at IS NULL = hold is active and participates in conflict detection.
-- released_at IS NOT NULL = hold has been released (confirm/cancel/expiry); excluded from EXCLUDE.
-- This mirrors the availability_released_at IS NULL pattern on bookings.
--
-- booking_id is a soft (non-FK) reference: bookings is hash-partitioned by host_id,
-- which makes ON DELETE CASCADE across all 16 partitions impractical. Holds are always
-- released explicitly by the confirm/cancel/expiry paths.

CREATE TABLE collective_participant_holds (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    booking_id     UUID        NOT NULL,
    participant_id UUID        NOT NULL REFERENCES users(id),
    start_time     TIMESTAMPTZ NOT NULL,
    end_time       TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    released_at    TIMESTAMPTZ,
    CONSTRAINT collective_participant_holds_pkey PRIMARY KEY (id),
    EXCLUDE USING gist (
        participant_id WITH =,
        tstzrange(start_time, end_time, '[)') WITH &&
    ) WHERE (released_at IS NULL)
);

CREATE INDEX idx_cph_booking_id ON collective_participant_holds(booking_id);
CREATE INDEX idx_cph_expires_at ON collective_participant_holds(expires_at)
    WHERE (released_at IS NULL);
