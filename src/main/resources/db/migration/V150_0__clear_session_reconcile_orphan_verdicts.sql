-- Clear PROVIDER_STATE_ORPHANED from SESSION sync jobs.
--
-- BookingSyncReconciler.findSyncedCandidates was not scoped by internal_ref_type, so it picked up
-- SESSION jobs and observed them through CalendarProviderClient, which resolves the host with
-- bookingRepository.findAnyById(internalRefId). A session id is not a booking id, so that lookup
-- threw 404 -> INVALID_REQUEST -> PERMANENT_FAILURE -> PROVIDER_STATE_ORPHANED on the first pass.
--
-- The verdict described our own lookup, not the provider: these events exist and sync fine through
-- SessionSyncWorker. It was also self-sustaining, because last_error = 'PROVIDER_STATE_ORPHANED'
-- is exactly what the candidate query filters on, so an affected job was never re-examined. The
-- query is now scoped to BOOKING; this clears the rows it already mislabelled.
--
-- Narrow by design. Only SESSION rows carrying this specific rationale are touched: an orphan
-- verdict on a BOOKING row was reached through a working lookup and may well be real, and the
-- other terminal states (TERMINAL_EXTERNAL_DELETE, EXTERNAL_ACTION_REQUIRED) are untouched
-- regardless of ref type. status is left alone -- these jobs are SYNCED and stay SYNCED; only the
-- error verdict was wrong.
UPDATE calendar_sync_jobs
   SET last_error = NULL,
       updated_at = NOW()
 WHERE internal_ref_type = 'SESSION'
   AND last_error = 'PROVIDER_STATE_ORPHANED';
