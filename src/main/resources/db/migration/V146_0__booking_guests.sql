-- Additional guests attached to a booking by the person doing the booking.
--
-- Until now a booking reached exactly two people: the host, and the one guest named by
-- bookings.guest_email. A booker who wanted a colleague in the meeting had to forward the invite by
-- hand, which left that colleague off the calendar event and off every later reschedule or
-- cancellation notice. This table is the fan-out list that fixes it, read by both
-- IcsInviteGenerator (which emits each row as an ATTENDEE) and BookingNotificationService (which
-- sends each row its own mail).
--
-- A child table rather than an array column on bookings, because both readers iterate it and the
-- unique index below is what actually enforces de-duplication. Emails are stored already trimmed
-- and lower-cased by PublicBookingService.updateGuestDetails, so that index does real work instead
-- of admitting "A@x.com" alongside "a@x.com".
--
-- host_id is denormalised for one reason: bookings is HASH-partitioned on host_id with composite
-- primary key (id, host_id), so a child row cannot reference it without carrying the partition key.
-- The FK must therefore be composite. This mirrors booking_question_answers (V93_0) exactly.
--
-- Guests are invite-only by design. There is deliberately no status column, no updated_at and no
-- trigger: rows are inserted and deleted, never mutated. updateGuestDetails replaces the whole set
-- on each submit, because the details step is re-submittable and appending would collide with the
-- unique index. No name column either — the chip field collects addresses only, and ICS renders
-- correctly from a bare address.
--
-- ON DELETE CASCADE is the whole lifecycle: guests live and die with their booking. Cancellation
-- does not delete the row (it is a status transition), so cancelled bookings keep their guests and
-- those guests still receive the cancellation notice.

CREATE TABLE booking_guests (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID         NOT NULL,
    host_id     UUID         NOT NULL,
    guest_email VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    FOREIGN KEY (booking_id, host_id) REFERENCES bookings(id, host_id) ON DELETE CASCADE
);

-- Leads with (booking_id, host_id) so it also serves the load-all-guests-for-a-booking query that
-- the ICS and notification paths both run. A second plain index would be redundant.
CREATE UNIQUE INDEX idx_booking_guests_unique
    ON booking_guests (booking_id, host_id, guest_email);
