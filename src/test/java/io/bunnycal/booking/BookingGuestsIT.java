package io.bunnycal.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.booking.domain.BookingGuest;
import io.bunnycal.booking.repository.BookingGuestRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Exercises the V146_0 schema against real Postgres.
 *
 * <p>The composite foreign key is the part worth proving: {@code bookings} is HASH-partitioned on
 * {@code host_id} with primary key {@code (id, host_id)}, so a single-column reference would not
 * even create. These assertions fail loudly if that ever regresses.
 */
class BookingGuestsIT extends AbstractBookingIT {

    @Autowired
    private BookingGuestRepository bookingGuestRepository;

    /**
     * Distinct times per booking: ux_booking_conflict makes (host_id, start_time, end_time)
     * unique across PENDING and CONFIRMED rows, so two bookings for one host must not share a
     * slot.
     */
    private UUID newBooking(UUID hostId, int hourOffset) {
        Instant start = Instant.parse("2026-05-10T10:00:00Z").plusSeconds(hourOffset * 3600L);
        return insertBooking(hostId, UUID.randomUUID(), start, start.plusSeconds(1800),
                "PENDING", 0L);
    }

    private UUID newBooking(UUID hostId) {
        return newBooking(hostId, 0);
    }

    @Test
    void guestsRoundTripAndLoadByBookingAndHost() {
        var host = createHost();
        UUID bookingId = newBooking(host.getId());

        bookingGuestRepository.saveAll(List.of(
                BookingGuest.builder().bookingId(bookingId).hostId(host.getId())
                        .guestEmail("colleague@example.com").build(),
                BookingGuest.builder().bookingId(bookingId).hostId(host.getId())
                        .guestEmail("second@example.com").build()));

        List<BookingGuest> loaded =
                bookingGuestRepository.findByBookingIdAndHostId(bookingId, host.getId());

        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().anyMatch(g -> g.getGuestEmail().equals("colleague@example.com")));
        // created_at is defaulted by the database, not the application.
        assertTrue(loaded.stream().allMatch(g -> g.getCreatedAt() != null));
    }

    @Test
    void uniqueIndexRejectsTheSameAddressTwiceOnOneBooking() {
        var host = createHost();
        UUID bookingId = newBooking(host.getId());

        bookingGuestRepository.saveAndFlush(BookingGuest.builder()
                .bookingId(bookingId).hostId(host.getId())
                .guestEmail("colleague@example.com").build());

        // The service normalises to lower case before writing, which is what makes this index a
        // real de-duplication guarantee rather than a case-sensitive near-miss.
        assertThrows(DataIntegrityViolationException.class, () ->
                bookingGuestRepository.saveAndFlush(BookingGuest.builder()
                        .bookingId(bookingId).hostId(host.getId())
                        .guestEmail("colleague@example.com").build()));
    }

    @Test
    void sameAddressIsAllowedOnADifferentBooking() {
        var host = createHost();
        UUID first = newBooking(host.getId(), 0);
        UUID second = newBooking(host.getId(), 1);

        bookingGuestRepository.saveAndFlush(BookingGuest.builder()
                .bookingId(first).hostId(host.getId()).guestEmail("colleague@example.com").build());
        bookingGuestRepository.saveAndFlush(BookingGuest.builder()
                .bookingId(second).hostId(host.getId()).guestEmail("colleague@example.com").build());

        assertEquals(1, bookingGuestRepository.findByBookingIdAndHostId(first, host.getId()).size());
        assertEquals(1, bookingGuestRepository.findByBookingIdAndHostId(second, host.getId()).size());
    }

    @Test
    void deletingTheBookingCascadesToItsGuests() {
        var host = createHost();
        UUID bookingId = newBooking(host.getId());
        bookingGuestRepository.saveAndFlush(BookingGuest.builder()
                .bookingId(bookingId).hostId(host.getId()).guestEmail("colleague@example.com").build());

        jdbc.update("DELETE FROM bookings WHERE id = ? AND host_id = ?", bookingId, host.getId());

        assertEquals(0, bookingGuestRepository.findByBookingIdAndHostId(bookingId, host.getId()).size());
    }

    /**
     * Cancellation is a status transition, never a row delete — so the cascade above must not
     * fire, and the guests stay attached to receive the cancellation notice.
     */
    @Test
    void cancellingTheBookingKeepsItsGuests() {
        var host = createHost();
        UUID bookingId = newBooking(host.getId());
        bookingGuestRepository.saveAndFlush(BookingGuest.builder()
                .bookingId(bookingId).hostId(host.getId()).guestEmail("colleague@example.com").build());

        jdbc.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ? AND host_id = ?",
                bookingId, host.getId());

        assertEquals(1, bookingGuestRepository.findByBookingIdAndHostId(bookingId, host.getId()).size());
    }

    @Test
    void deleteByBookingAndHostClearsOnlyThatBooking() {
        var host = createHost();
        UUID first = newBooking(host.getId(), 0);
        UUID second = newBooking(host.getId(), 1);
        bookingGuestRepository.saveAndFlush(BookingGuest.builder()
                .bookingId(first).hostId(host.getId()).guestEmail("a@example.com").build());
        bookingGuestRepository.saveAndFlush(BookingGuest.builder()
                .bookingId(second).hostId(host.getId()).guestEmail("b@example.com").build());

        inTx(() -> bookingGuestRepository.deleteByBookingIdAndHostId(first, host.getId()));

        assertEquals(0, bookingGuestRepository.findByBookingIdAndHostId(first, host.getId()).size());
        assertEquals(1, bookingGuestRepository.findByBookingIdAndHostId(second, host.getId()).size());
    }

    /**
     * The read path: guests attached to a booking come back out again. Without this the rows are
     * written and mailed but invisible, so a host sees "one-to-one" for a four-person meeting.
     */
    @Test
    void savedGuestsAreReadableForDisplay() {
        var host = createHost();
        UUID bookingId = newBooking(host.getId());
        bookingGuestRepository.saveAll(List.of(
                BookingGuest.builder().bookingId(bookingId).hostId(host.getId())
                        .guestEmail("first@example.com").build(),
                BookingGuest.builder().bookingId(bookingId).hostId(host.getId())
                        .guestEmail("second@example.com").build()));

        List<String> emails = bookingGuestRepository.findByBookingIdAndHostId(bookingId, host.getId())
                .stream().map(BookingGuest::getGuestEmail).sorted().toList();

        assertEquals(List.of("first@example.com", "second@example.com"), emails);
    }

    /** A booking with no guests reads as an empty list, never null. */
    @Test
    void aBookingWithoutGuestsReadsAsEmpty() {
        var host = createHost();
        UUID bookingId = newBooking(host.getId());

        assertTrue(bookingGuestRepository.findByBookingIdAndHostId(bookingId, host.getId()).isEmpty());
    }
}
