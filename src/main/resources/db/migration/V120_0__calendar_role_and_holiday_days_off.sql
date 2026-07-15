-- Calendar roles: only the primary is shown and blocks by default. Holiday calendars feed days-off
-- (not busy time); everything else (birthdays, subscribed feeds, secondary calendars) does nothing.
--
-- This corrects a default introduced in V119_0: checks_availability defaulted TRUE on *every*
-- calendar, so holidays and birthdays started blocking booking slots. From here, only the primary
-- checks availability out of the box.

ALTER TABLE calendar_connection_calendars
    ADD COLUMN IF NOT EXISTS calendar_role VARCHAR(16) NOT NULL DEFAULT 'OTHER';

COMMENT ON COLUMN calendar_connection_calendars.calendar_role IS
    'PRIMARY | HOLIDAY | OTHER. Only PRIMARY is shown on the integrations page and checks '
    'availability by default. HOLIDAY feeds whole-day-off (never busy time). OTHER (birthdays, '
    'feeds, secondary) is never shown, synced, or blocking. Classified at hydration; errs to OTHER.';

ALTER TABLE calendar_connection_calendars
    ALTER COLUMN checks_availability SET DEFAULT FALSE;

COMMENT ON COLUMN calendar_connection_calendars.checks_availability IS
    'Only the PRIMARY calendar may be toggled for free/busy. HOLIDAY feeds days-off through a '
    'separate path; OTHER never affects availability.';

-- 1. Primary flag wins.
UPDATE calendar_connection_calendars SET calendar_role = 'PRIMARY' WHERE is_primary = TRUE;

-- 2. Holiday calendars.
--    Google: the id is stable and language-independent (…#holiday@group.v.calendar.google.com).
--    Microsoft: no machine signal, only the (localised) name — match the word "holiday(s)".
UPDATE calendar_connection_calendars c
SET calendar_role = 'HOLIDAY'
FROM calendar_connections conn
WHERE c.connection_id = conn.id
  AND c.is_primary = FALSE
  AND (
        (conn.provider = 'GOOGLE'
             AND c.external_calendar_id ILIKE '%#holiday@group.v.calendar.google.com')
     OR (conn.provider <> 'GOOGLE'
             AND c.name ~* '\yholidays?\y')
  );

-- 3. Reset availability to the new default: only the primary checks for conflicts. Holiday and
--    other calendars stop blocking as busy time. (A user who had deliberately turned the primary
--    off keeps that — we only force the non-primaries off, we do not force the primary on.)
UPDATE calendar_connection_calendars
SET checks_availability = FALSE
WHERE calendar_role <> 'PRIMARY';

-- New rows default to OTHER at the column level; hydration reclassifies and sets the primary's
-- availability on. Flip the column default so a bare insert is safe.
ALTER TABLE calendar_connection_calendars
    ALTER COLUMN calendar_role SET DEFAULT 'OTHER';
