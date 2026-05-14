ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS availability_released_at TIMESTAMPTZ;

DROP INDEX IF EXISTS idx_bookings_status_updated;
CREATE INDEX IF NOT EXISTS idx_bookings_status_updated
    ON bookings (status, updated_at)
    WHERE status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL;

ALTER TABLE bookings_p00 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p00;
ALTER TABLE bookings_p01 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p01;
ALTER TABLE bookings_p02 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p02;
ALTER TABLE bookings_p03 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p03;
ALTER TABLE bookings_p04 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p04;
ALTER TABLE bookings_p05 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p05;
ALTER TABLE bookings_p06 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p06;
ALTER TABLE bookings_p07 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p07;
ALTER TABLE bookings_p08 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p08;
ALTER TABLE bookings_p09 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p09;
ALTER TABLE bookings_p10 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p10;
ALTER TABLE bookings_p11 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p11;
ALTER TABLE bookings_p12 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p12;
ALTER TABLE bookings_p13 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p13;
ALTER TABLE bookings_p14 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p14;
ALTER TABLE bookings_p15 DROP CONSTRAINT IF EXISTS bookings_no_overlap_p15;

ALTER TABLE bookings_p00 ADD CONSTRAINT bookings_no_overlap_p00 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p01 ADD CONSTRAINT bookings_no_overlap_p01 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p02 ADD CONSTRAINT bookings_no_overlap_p02 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p03 ADD CONSTRAINT bookings_no_overlap_p03 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p04 ADD CONSTRAINT bookings_no_overlap_p04 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p05 ADD CONSTRAINT bookings_no_overlap_p05 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p06 ADD CONSTRAINT bookings_no_overlap_p06 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p07 ADD CONSTRAINT bookings_no_overlap_p07 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p08 ADD CONSTRAINT bookings_no_overlap_p08 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p09 ADD CONSTRAINT bookings_no_overlap_p09 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p10 ADD CONSTRAINT bookings_no_overlap_p10 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p11 ADD CONSTRAINT bookings_no_overlap_p11 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p12 ADD CONSTRAINT bookings_no_overlap_p12 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p13 ADD CONSTRAINT bookings_no_overlap_p13 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p14 ADD CONSTRAINT bookings_no_overlap_p14 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
ALTER TABLE bookings_p15 ADD CONSTRAINT bookings_no_overlap_p15 EXCLUDE USING gist (host_id WITH =, tstzrange(start_time, end_time) WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND availability_released_at IS NULL);
