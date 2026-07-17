-- Calendar events ingested before V64_0 have no provider-calendar attribution. At that time each
-- connection synced only its primary calendar, so that connection's primary inventory row is the
-- deterministic attribution. Backfilling lets the versioned availability policy honor an explicit
-- off switch instead of treating these rows as global blockers.
--
-- Rows without exactly one primary inventory candidate remain NULL. The runtime query scopes those
-- rows to an enabled readable visible primary on the same connection, preserving safety without
-- bypassing calendar selection.
WITH unique_primary AS (
    SELECT connection_id, MIN(external_calendar_id) AS external_calendar_id
    FROM calendar_connection_calendars
    WHERE calendar_role = 'PRIMARY'
    GROUP BY connection_id
    HAVING COUNT(*) = 1
)
UPDATE calendar_events event
SET external_calendar_id = primary_calendar.external_calendar_id
FROM unique_primary primary_calendar
WHERE event.connection_id = primary_calendar.connection_id
  AND event.external_calendar_id IS NULL;
