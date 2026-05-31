-- =====================================================================
-- Bookings persistence layer.
--
-- Enforces system_contracts.md Invariants #1 (non-overlap), #2 (terminal
-- state reachable), #4 (durability) at the database. The DB is the
-- authoritative source of truth for the write path; application-level
-- pre-checks are convenience, not correctness.
-- =====================================================================

-- btree_gist gives us the `=` operator class for UUID inside a GiST
-- index, which the EXCLUDE constraint requires for `host_id WITH =`.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------
-- Partitioning strategy: HASH on host_id, 16 partitions.
--
-- Why hash on host_id and not time bucket:
--
--   * Time-bucket partitioning concentrates every "right now" write
--     onto the single current-window child. Concurrent booking
--     creation across all hosts would serialize on that one partition's
--     GiST index. The exact hotspot we want to avoid.
--
--   * Hash on host_id spreads concurrent inserts across N independent
--     children. Each child has its own GiST index, so writes for hosts
--     in different partitions never contend for the same index pages.
--
--   * Every read on the hot path filters on `host_id = ?` (overlap
--     check, host's calendar). Partition pruning routes each query to
--     exactly one child, so plans remain efficient as host count grows.
--
--   * Skew: a mega-host saturates its partition, but the blast radius is
--     bounded to 1/N. That class of skew requires application-side
--     concurrency limits, not more partitions.
--
-- N = 16: enough scatter for typical hardware, cheap to plan over.
-- Resizing is offline + heavy; pick a generous N up front.
--
-- Key constraint placement note:
--   PostgreSQL does NOT support EXCLUDE constraints on a partitioned
--   parent table (as of PG 16). The EXCLUDE is placed on each child
--   partition via ALTER TABLE after creation. This is CORRECT for our
--   model: hash(host_id) guarantees all bookings for a given host land
--   in the same partition, so a per-partition EXCLUDE enforces the
--   global non-overlap invariant.
-- ---------------------------------------------------------------------

CREATE TABLE bookings (
    id           UUID         NOT NULL,
    host_id      UUID         NOT NULL,
    start_time   TIMESTAMPTZ  NOT NULL,
    end_time     TIMESTAMPTZ  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    expires_at   TIMESTAMPTZ,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Partition key must appear in every unique/PK constraint.
    CONSTRAINT bookings_pkey PRIMARY KEY (id, host_id),

    CONSTRAINT bookings_status_check
        CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','EXPIRED','COMPLETED','REJECTED')),

    CONSTRAINT bookings_time_order_check
        CHECK (start_time < end_time)
)
PARTITION BY HASH (host_id);

CREATE TABLE bookings_p00 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER  0);
CREATE TABLE bookings_p01 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER  1);
CREATE TABLE bookings_p02 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER  2);
CREATE TABLE bookings_p03 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER  3);
CREATE TABLE bookings_p04 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER  4);
CREATE TABLE bookings_p05 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER  5);
CREATE TABLE bookings_p06 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER  6);
CREATE TABLE bookings_p07 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER  7);
CREATE TABLE bookings_p08 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER  8);
CREATE TABLE bookings_p09 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER  9);
CREATE TABLE bookings_p10 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER 10);
CREATE TABLE bookings_p11 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER 11);
CREATE TABLE bookings_p12 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER 12);
CREATE TABLE bookings_p13 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER 13);
CREATE TABLE bookings_p14 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER 14);
CREATE TABLE bookings_p15 PARTITION OF bookings FOR VALUES WITH (MODULUS 16, REMAINDER 15);

-- Invariant #1 enforcer: no two PENDING/CONFIRMED bookings for the same
-- host may overlap. Each partition gets its own EXCLUDE constraint with
-- the same name. When the error fires (SQLState 23P01), the message
-- contains "bookings_no_overlap" regardless of which partition caught it,
-- which is what BookingService.isOverlapExclusionViolation() keys on.
--
-- Note on tstzrange vs tsrange: columns are TIMESTAMPTZ. tsrange takes
-- `timestamp without time zone`, so passing TIMESTAMPTZ forces an implicit
-- cast through the session's TimeZone GUC — overlap semantics would silently
-- shift per-connection. tstzrange is the type-safe match.
-- Each EXCLUDE constraint creates a backing GiST index. PG requires unique
-- index names within a schema, so each partition gets a suffixed name.
-- BookingService.isOverlapExclusionViolation() uses a prefix-match on
-- "bookings_no_overlap", which hits every bookings_no_overlap_pXX name.
ALTER TABLE bookings_p00 ADD CONSTRAINT bookings_no_overlap_p00 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p01 ADD CONSTRAINT bookings_no_overlap_p01 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p02 ADD CONSTRAINT bookings_no_overlap_p02 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p03 ADD CONSTRAINT bookings_no_overlap_p03 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p04 ADD CONSTRAINT bookings_no_overlap_p04 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p05 ADD CONSTRAINT bookings_no_overlap_p05 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p06 ADD CONSTRAINT bookings_no_overlap_p06 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p07 ADD CONSTRAINT bookings_no_overlap_p07 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p08 ADD CONSTRAINT bookings_no_overlap_p08 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p09 ADD CONSTRAINT bookings_no_overlap_p09 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p10 ADD CONSTRAINT bookings_no_overlap_p10 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p11 ADD CONSTRAINT bookings_no_overlap_p11 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p12 ADD CONSTRAINT bookings_no_overlap_p12 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p13 ADD CONSTRAINT bookings_no_overlap_p13 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p14 ADD CONSTRAINT bookings_no_overlap_p14 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));
ALTER TABLE bookings_p15 ADD CONSTRAINT bookings_no_overlap_p15 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED'));

-- Hot read path: "list bookings for host H starting in window [s, e)".
-- Partition pruning isolates the lookup to one child.
CREATE INDEX idx_bookings_host_start
    ON bookings (host_id, start_time);

-- Reaper / sweeper path: partial on active set so terminal rows don't bloat.
CREATE INDEX idx_bookings_status_updated
    ON bookings (status, updated_at)
    WHERE status IN ('PENDING','CONFIRMED');

-- DB-owned updated_at (PG 13+ propagates row-level triggers to all children).
CREATE OR REPLACE FUNCTION bookings_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bookings_set_updated_at
BEFORE UPDATE ON bookings
FOR EACH ROW
EXECUTE FUNCTION bookings_set_updated_at();
