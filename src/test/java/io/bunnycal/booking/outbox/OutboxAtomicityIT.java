package io.bunnycal.booking.outbox;

import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.service.BookingService;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the atomic guarantee of the transactional outbox:
 *
 *   commit  → booking row AND outbox event both exist
 *   rollback → neither booking row NOR outbox event exist
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxAtomicityIT extends AbstractBookingIT {

    @Autowired BookingService bookingService;

    private UUID hostId;
    private UUID eventTypeId;

    @BeforeEach
    void setup() {
        User host = createHost();
        hostId = host.getId();
        eventTypeId = UUID.randomUUID();
    }

    @Test
    void commit_bookingAndOutboxEventBothExist() {
        Instant start = Instant.parse("2030-06-01T09:00:00Z");
        Instant end   = Instant.parse("2030-06-01T10:00:00Z");

        Booking saved = bookingService.createBooking(hostId, eventTypeId, start, end);

        assertNotNull(saved.getId());

        // Booking row persisted
        int bookingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE id = ?",
                Integer.class, saved.getId());
        assertEquals(1, bookingCount);

        // Outbox event persisted in the same commit
        int eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'BOOKING_CREATED'",
                Integer.class, saved.getId());
        assertEquals(1, eventCount);

        // Event starts in PENDING state, ready for the worker
        String status = jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE aggregate_id = ?",
                String.class, saved.getId());
        assertEquals("PENDING", status);
    }

    @Test
    void rollback_neitherBookingNorOutboxEventExist() {
        Instant start = Instant.parse("2030-06-01T09:00:00Z");
        Instant end   = Instant.parse("2030-06-01T10:00:00Z");

        // First booking succeeds
        bookingService.createBooking(hostId, eventTypeId, start, end);

        // Second booking overlaps — EXCLUDE constraint fires, TX rolls back
        assertThrows(CustomException.class,
                () -> bookingService.createBooking(hostId, eventTypeId, start, end));

        // Only one booking row exists (the first)
        int bookingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE host_id = ?",
                Integer.class, hostId);
        assertEquals(1, bookingCount);

        // Only one outbox event exists — the rolled-back event was never committed
        int eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'BOOKING_CREATED'",
                Integer.class);
        assertEquals(1, eventCount);
    }
}
