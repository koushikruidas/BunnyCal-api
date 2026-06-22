ALTER TABLE calendar_events
    ADD COLUMN IF NOT EXISTS blocks_availability BOOLEAN NOT NULL DEFAULT TRUE;

-- Session sync jobs historically used a placeholder provider value because the
-- actual projection provider was resolved late. Promote them to the real
-- projection provider so downstream ingestion can recognize session projections
-- deterministically and exclude them from availability blocking.
UPDATE calendar_sync_jobs csj
SET provider = LOWER(et.projection_provider::text)
FROM event_sessions s
JOIN event_types et ON et.id = s.event_type_id
WHERE csj.internal_ref_type = 'SESSION'
  AND csj.internal_ref_id = s.id
  AND et.projection_provider IS NOT NULL
  AND csj.provider = 'DEFERRED';

-- Backfill the non-blocking flag for any previously ingested session projection
-- rows. These are still projected calendar events, but they must not hide slots
-- until the underlying session itself becomes FULL.
UPDATE calendar_events ce
SET blocks_availability = FALSE
FROM calendar_sync_jobs csj
JOIN event_sessions s ON s.id = csj.internal_ref_id
JOIN event_types et ON et.id = s.event_type_id
WHERE csj.internal_ref_type = 'SESSION'
  AND csj.external_event_id IS NOT NULL
  AND ce.connection_id = et.projection_connection_id
  AND ce.provider = LOWER(et.projection_provider::text)
  AND ce.external_event_id = csj.external_event_id;
