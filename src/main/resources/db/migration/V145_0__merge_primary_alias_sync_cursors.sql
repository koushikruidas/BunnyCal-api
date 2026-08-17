-- Collapse Google sync-cursor rows stored under the literal "primary" alias onto the
-- concrete calendar id they actually refer to.
--
-- resolveCalendarsToSync falls back to "primary" whenever a connection's calendar inventory
-- has not been hydrated yet, which is always true during the initial connect. The alias and
-- the resolved id (the account's email address) name the SAME Google calendar, so a
-- connection could end up holding a cursor row for each. Once both existed, the next write
-- violated uk_calendar_connection_sync_cursors_connection_calendar, rolled the sync
-- transaction back, and left the scheduler marking the connection FAILED -- which on a fresh
-- signup aborted onboarding's calendar setup and produced a spurious "reconnect your
-- calendar" prompt.
--
-- GoogleIncrementalSyncObservationClient.persistSyncToken now resolves the alias before
-- writing, so no new alias rows are created. This clears the ones already stored.

-- 1. Drop an alias row when a real row for the same connection's primary calendar already
--    exists. The real row is authoritative: it is the one every post-hydration sync uses.
DELETE FROM calendar_connection_sync_cursors alias
USING calendar_connection_calendars cal,
      calendar_connection_sync_cursors real_row
WHERE alias.external_calendar_id = 'primary'
  AND cal.connection_id = alias.connection_id
  AND cal.is_primary = TRUE
  AND real_row.connection_id = alias.connection_id
  AND real_row.external_calendar_id = cal.external_calendar_id;

-- 2. Otherwise rename the alias row to the primary calendar's real id, preserving its cursor
--    so the next sync stays incremental instead of re-bootstrapping the whole window.
UPDATE calendar_connection_sync_cursors alias
SET external_calendar_id = cal.external_calendar_id,
    updated_at = now()
FROM calendar_connection_calendars cal
WHERE alias.external_calendar_id = 'primary'
  AND cal.connection_id = alias.connection_id
  AND cal.is_primary = TRUE
  AND cal.external_calendar_id IS NOT NULL
  AND cal.external_calendar_id <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM calendar_connection_sync_cursors other
      WHERE other.connection_id = alias.connection_id
        AND other.external_calendar_id = cal.external_calendar_id
  );

-- Any "primary" row left here belongs to a connection whose inventory still names no primary
-- calendar. It is left alone deliberately: there is no id to map it onto, and the application
-- keeps treating it as the alias until hydration resolves one.
