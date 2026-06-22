-- Drop the FK constraint that ties booking_action_tokens exclusively to the bookings table.
-- Session registrations reuse this table for capability tokens; they are keyed on
-- (registration_id, host_id) instead of (booking_id, booking_host_id).
ALTER TABLE booking_action_tokens
    DROP CONSTRAINT IF EXISTS fk_booking_action_tokens_booking;
