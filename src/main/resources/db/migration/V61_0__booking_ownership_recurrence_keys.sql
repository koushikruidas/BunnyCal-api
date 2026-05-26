ALTER TABLE booking_ownership
    ADD COLUMN IF NOT EXISTS series_external_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS instance_external_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS recurrence_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_booking_ownership_series_external_id
    ON booking_ownership(series_external_id);

CREATE INDEX IF NOT EXISTS idx_booking_ownership_instance_external_id
    ON booking_ownership(instance_external_id);
