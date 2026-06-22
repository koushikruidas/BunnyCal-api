-- Cleanup migration:
-- session_calendar_event_mappings is not part of the final session sync architecture.
-- Session external event identity is tracked by calendar_sync_jobs.external_event_id.
-- Stale-write protection is tracked by event_sessions.calendar_sequence.

DROP TABLE IF EXISTS session_calendar_event_mappings;
