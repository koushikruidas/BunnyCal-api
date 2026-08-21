-- The group attendee's own timezone, captured at registration time.
--
-- V147_0 gave 1:1, round-robin and collective bookings a guest_timezone so their confirmation,
-- reschedule and cancellation mails could state the time in the recipient's own zone. GROUP was
-- excluded from that change for one reason: its registrations live in session_registrations, not
-- bookings, and the column did not exist here. So every group attendee still reads the HOST's
-- zone -- "13:30-14:00 (Asia/Kolkata)" to someone in New York, a real time but not theirs.
--
-- This is that missing column. The value arrives on the same X-Timezone header the start time is
-- already normalised against (PublicBookingController#hold), so nothing new has to be collected
-- from the client; PublicBookingService simply stopped short of persisting it for this one kind.
--
-- NULLABLE and permanently so, for the same reasons V147_0 gave: rows written before this
-- migration have no zone, and non-browser registration paths may never carry one. Readers MUST
-- fall back to the host's timezone, never to UTC -- falling back to UTC would re-render the time
-- on every historical registration, a wider regression than the bug being fixed.
--
-- VARCHAR(64) matches the IANA zone-id shape and mirrors bookings.guest_timezone and
-- users.timezone. Values are validated against ZoneId before they reach this column, so no CHECK
-- constraint is needed -- the tz database changes faster than a constraint could track.
--
-- No index: only ever read alongside a registration already located by session or primary key.

ALTER TABLE session_registrations
    ADD COLUMN IF NOT EXISTS guest_timezone VARCHAR(64);
