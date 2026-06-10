-- Collective event type: one calendar-event mapping per participant per provider.
-- Step 1: add participant_user_id nullable and backfill from bookings.host_id.
-- Existing ONE_ON_ONE and ROUND_ROBIN bookings have exactly one participant (the host),
-- so host_id is the correct backfill value.

ALTER TABLE calendar_event_mappings
    ADD COLUMN participant_user_id UUID;

UPDATE calendar_event_mappings m
SET participant_user_id = b.host_id
FROM bookings b
WHERE b.id = m.booking_id;
