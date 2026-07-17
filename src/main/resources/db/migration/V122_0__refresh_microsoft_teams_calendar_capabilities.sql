-- V121 was briefly applied in local development with NOT NULL DEFAULT FALSE before its committed
-- nullable form was finalized. Those false values look like authoritative Graph answers, so the
-- runtime inventory refresh never re-checks allowedOnlineMeetingProviders for existing Microsoft
-- calendars. Restore "unknown" for Microsoft rows and let the normal inventory hydration populate
-- the real per-calendar capability from Graph.
ALTER TABLE calendar_connection_calendars
    ALTER COLUMN supports_native_teams DROP DEFAULT,
    ALTER COLUMN supports_native_teams DROP NOT NULL;

UPDATE calendar_connection_calendars calendar
SET supports_native_teams = NULL
FROM calendar_connections connection
WHERE connection.id = calendar.connection_id
  AND lower(connection.provider) = 'microsoft';
