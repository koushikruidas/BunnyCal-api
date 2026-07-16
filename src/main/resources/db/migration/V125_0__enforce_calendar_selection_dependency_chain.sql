-- Enforce the settings dependency chain for rows created before the runtime guard existed:
--
-- availability -> writeback -> native conferencing
--
-- Zoom is provider-independent and is intentionally untouched.

-- A calendar that does not contribute to availability cannot remain the projection target.
UPDATE calendar_connection_calendars calendar
SET is_selected = FALSE
FROM calendar_connections connection
WHERE calendar.connection_id = connection.id
  AND calendar.is_selected = TRUE
  AND (
        calendar.checks_availability = FALSE
     OR calendar.calendar_role <> 'PRIMARY'
     OR calendar.can_read = FALSE
     OR calendar.can_write = FALSE
     OR calendar.hidden = TRUE
     OR connection.status IN ('DISCONNECTED', 'REVOKED')
  );

-- A connection with no eligible selected calendar cannot remain the user's writeback connection.
UPDATE calendar_connections connection
SET is_default_writeback = FALSE
WHERE connection.is_default_writeback = TRUE
  AND NOT EXISTS (
        SELECT 1
        FROM calendar_connection_calendars calendar
        WHERE calendar.connection_id = connection.id
          AND calendar.is_selected = TRUE
          AND calendar.checks_availability = TRUE
          AND calendar.calendar_role = 'PRIMARY'
          AND calendar.can_read = TRUE
          AND calendar.can_write = TRUE
          AND calendar.hidden = FALSE
  );

-- Native conferencing cannot survive without a compatible eligible writeback calendar. Independent
-- conferencing choices such as ZOOM remain valid without any calendar.
UPDATE users user_account
SET default_conferencing_provider = 'NONE'
WHERE user_account.default_conferencing_provider IN ('GOOGLE_MEET', 'MICROSOFT_TEAMS')
  AND NOT EXISTS (
        SELECT 1
        FROM calendar_connections connection
        JOIN calendar_connection_calendars calendar
          ON calendar.connection_id = connection.id
        WHERE connection.user_id = user_account.id
          AND connection.is_default_writeback = TRUE
          AND connection.status = 'ACTIVE'
          AND calendar.is_selected = TRUE
          AND calendar.checks_availability = TRUE
          AND calendar.calendar_role = 'PRIMARY'
          AND calendar.can_read = TRUE
          AND calendar.can_write = TRUE
          AND calendar.hidden = FALSE
          AND (
                (user_account.default_conferencing_provider = 'GOOGLE_MEET'
                    AND connection.provider = 'GOOGLE')
             OR (user_account.default_conferencing_provider = 'MICROSOFT_TEAMS'
                    AND connection.provider = 'MICROSOFT'
                    AND calendar.supports_native_teams = TRUE)
          )
  );
