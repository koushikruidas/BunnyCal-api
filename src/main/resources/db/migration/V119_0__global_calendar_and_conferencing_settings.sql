-- Global calendar & conferencing settings.
--
-- Availability, write-back and the default meeting link move from the event type to the user.
-- An event type now stores either the pointer DEFAULT ("use my global default", resolved live at
-- booking time) or a provider-independent override (ZOOM / CUSTOM_URL / NONE). GOOGLE_MEET and
-- MICROSOFT_TEAMS are reachable only through the pointer, so no event type can hold a conferencing
-- provider that its owner's current write-back calendar cannot serve.
--
-- This migration must run AFTER V54/V55/V59/V66/V76, which read and write the event_types columns
-- dropped at the bottom. Flyway replays the whole chain on a fresh database, so the columns have to
-- exist while those run and may only be removed here, at the end.

-- 1. The user's global default meeting link.
--    NONE is the safe landing value: an event type resolving to NONE simply gets no join link,
--    which is a legitimate state. It never produces a broken link.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS default_conferencing_provider VARCHAR(32) NOT NULL DEFAULT 'NONE';

-- 2. Per-calendar availability.
--    sync_enabled already exists on this table with DEFAULT TRUE and is completely dormant: as of
--    V118_0 nothing in the application reads it and nothing writes it. Rather than add a fourth
--    near-synonym alongside is_selected / can_read / hidden, we claim it. The new name says what it
--    now means: this calendar is checked whenever anyone books its owner.
--
--    (The name `blocks_availability` was NOT available -- it already exists on calendar_events with
--    a different meaning: "this individual event marks me busy".)
ALTER TABLE calendar_connection_calendars
    RENAME COLUMN sync_enabled TO checks_availability;

COMMENT ON COLUMN calendar_connection_calendars.checks_availability IS
    'This calendar is read for free/busy whenever anyone books its owner -- on the owner''s own event '
    'types and on any team event (round-robin, collective) they participate in. Default TRUE: if you '
    'connected it, it blocks you.';

-- Defaulting this TRUE is a deliberate behaviour expansion. The create-event wizard only ever
-- offered PRIMARY calendars, so secondary calendars have never blocked anyone's slots. They now do.
UPDATE calendar_connection_calendars SET checks_availability = TRUE WHERE checks_availability IS NULL;

COMMENT ON COLUMN calendar_connection_calendars.is_selected IS
    'The write-back calendar within this connection -- the one confirmed bookings are written into. '
    'At most one per connection. Read by BookingSchedulingProjectionResolver; before V119_0 it was '
    'never written by application code, which made that resolver''s selected-calendar tier a dead '
    'branch. It is now written by the write-back setter.';

-- Exactly one write-back calendar per connection. Partial: zero is allowed (a connection whose
-- inventory has not been hydrated yet).
DROP INDEX IF EXISTS uk_calendar_connection_calendars_selected_per_connection;
CREATE UNIQUE INDEX IF NOT EXISTS ux_calendar_writeback_calendar_per_connection
    ON calendar_connection_calendars (connection_id)
    WHERE is_selected = TRUE;

-- Seed the write-back calendar on each connection that has none: prefer the primary, else the
-- lowest external id -- which is exactly the fallback BookingSchedulingProjectionResolver applied
-- implicitly, so this changes no behaviour. It only makes the choice explicit and editable.
WITH candidate AS (
    SELECT DISTINCT ON (c.connection_id) c.id
    FROM calendar_connection_calendars c
    WHERE c.can_write = TRUE
      AND NOT EXISTS (
          SELECT 1 FROM calendar_connection_calendars s
          WHERE s.connection_id = c.connection_id AND s.is_selected = TRUE)
    ORDER BY c.connection_id, c.is_primary DESC, c.external_calendar_id ASC
)
UPDATE calendar_connection_calendars t
SET is_selected = TRUE
FROM candidate
WHERE t.id = candidate.id;

-- 3. Seed each user's default meeting link from the provider of the calendar their bookings
--    already go to, so existing behaviour carries over rather than silently becoming NONE.
--    A consumer MSA (16-hex-char /me/id, as opposed to an Entra oid UUID) cannot mint Teams links
--    at all, so it lands on NONE.
UPDATE users u
SET default_conferencing_provider = CASE
        WHEN conn.provider = 'GOOGLE' THEN 'GOOGLE_MEET'
        WHEN conn.provider = 'MICROSOFT'
             AND conn.provider_user_id !~ '^[0-9a-fA-F]{16}$' THEN 'MICROSOFT_TEAMS'
        ELSE 'NONE'
    END
FROM calendar_connections conn
WHERE conn.user_id = u.id
  AND conn.is_default_writeback = TRUE;

-- 4. Drop the per-event-type calendar model.
--
--    NOTE: booking_ownership has identically-named projection_* columns and they STAY. Those are the
--    immutable per-booking record of where each event was ACTUALLY written, and cancel/reschedule
--    resolve through them. Only the event_types copies -- the frozen-at-creation intent -- go away.
DROP INDEX IF EXISTS idx_event_types_organizer_calendar_connection_id;

ALTER TABLE event_types
    DROP COLUMN IF EXISTS projection_provider,
    DROP COLUMN IF EXISTS projection_connection_id,
    DROP COLUMN IF EXISTS projection_calendar_id,
    DROP COLUMN IF EXISTS organizer_calendar_connection_id,
    DROP COLUMN IF EXISTS availability_calendars_json,
    DROP COLUMN IF EXISTS availability_mode;

-- 5. Existing event types keep working: every one of them was pinned to a provider under the old
--    model. Repoint the two provider-coupled values at the pointer so they follow their owner's
--    global default from now on instead of staying frozen. ZOOM / CUSTOM_URL / NONE are
--    provider-independent and stay exactly as they are.
UPDATE event_types
SET conferencing_provider = 'DEFAULT'
WHERE conferencing_provider IN ('GOOGLE_MEET', 'MICROSOFT_TEAMS');

ALTER TABLE event_types
    ALTER COLUMN conferencing_provider SET DEFAULT 'DEFAULT';
