# Session Calendar Identity

Session calendar identity is stored in `calendar_sync_jobs.external_event_id`.

Session stale-write protection is handled by `event_sessions.calendar_sequence`.

The legacy `session_calendar_event_mappings` table is not part of the final architecture and is dropped by `V75_0__drop_session_calendar_event_mappings.sql`.
