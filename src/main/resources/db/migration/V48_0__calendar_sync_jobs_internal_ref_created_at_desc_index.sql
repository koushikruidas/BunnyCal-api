-- Covering index for the LATERAL "most recent sync job per booking" lookup used by
-- BookingRepository.findMeetingsForHost / findUpcomingMeetingsForHost:
--
--   LEFT JOIN LATERAL (
--     SELECT j.last_error FROM calendar_sync_jobs j
--     WHERE j.internal_ref_type = 'BOOKING'
--       AND j.internal_ref_id = b.id
--       AND j.provider = 'google'
--     ORDER BY j.created_at DESC, j.id DESC
--     LIMIT 1
--   ) csj ON TRUE
--
-- Without this index the lateral has to filter on the existing
-- calendar_sync_jobs_unique_ref_provider unique index and then sort per row.
-- The new index serves the entire filter+sort in one descent; LIMIT 1 reads
-- a single tuple.
--
-- Safe to add: additive, no constraint changes.
CREATE INDEX IF NOT EXISTS idx_csj_internal_provider_created_desc
    ON calendar_sync_jobs (internal_ref_type, internal_ref_id, provider, created_at DESC, id DESC);
