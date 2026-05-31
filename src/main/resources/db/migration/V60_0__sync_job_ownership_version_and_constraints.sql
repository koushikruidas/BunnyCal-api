ALTER TABLE calendar_sync_jobs
    ADD COLUMN IF NOT EXISTS ownership_version BIGINT;

UPDATE calendar_sync_jobs j
SET ownership_version = bo.ownership_version
FROM booking_ownership bo
WHERE j.internal_ref_type = 'BOOKING'
  AND j.internal_ref_id = bo.booking_id
  AND j.ownership_version IS NULL;

UPDATE calendar_sync_jobs
SET ownership_version = 1
WHERE ownership_version IS NULL;

ALTER TABLE calendar_sync_jobs
    ALTER COLUMN ownership_version SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_booking_ownership_provider_external_event
    ON booking_ownership (projection_provider, provider_external_event_id)
    WHERE provider_external_event_id IS NOT NULL AND provider_external_event_id <> '';
