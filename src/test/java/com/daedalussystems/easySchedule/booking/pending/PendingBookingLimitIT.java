package com.daedalussystems.easySchedule.booking.pending;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.daedalussystems.easySchedule.booking.AbstractBookingIT;
import com.daedalussystems.easySchedule.booking.service.BookingService;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

// Integration tests for the phantom-pending-explosion guard.
//
// The guard is a countOverlappingPending() check inside BookingService that runs
// after the host-row SELECT FOR UPDATE. Serialisation by that lock makes the
// count stable within the transaction — no race is possible.
//
// Direct JDBC inserts are used to seed rows that bypass the service-layer limit
// so tests can place the system into states the service would never produce itself.
@Testcontainers(disabledWithoutDocker = true)
class PendingBookingLimitIT extends AbstractBookingIT {

    // Must match BookingService.MAX_PENDING_PER_HOST_PER_WINDOW.
    private static final int MAX_PENDING = BookingService.MAX_PENDING_PER_HOST_PER_WINDOW;

    @Autowired
    private BookingService bookingService;

    // ── Test 1 ───────────────────────────────────────────────────────────────

    // Pre-seed exactly MAX_PENDING PENDING bookings in non-overlapping adjacent
    // sub-slots that together fill [10:00, 11:00). Any request spanning the full
    // window sees countOverlappingPending() = MAX_PENDING and must be rejected with
    // TOO_MANY_PENDING_BOOKINGS — not SLOT_ALREADY_BOOKED — proving the pending
    // limit fires before the DB insert.
    //
    // The SELECT FOR UPDATE serialises all 50 concurrent threads; every thread
    // observes count = MAX_PENDING and fails identically.
    @Test
    void concurrentPendingLimit_enforced() throws Exception {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();

        insertBooking(hostId, "2026-07-01T10:00:00Z", "2026-07-01T10:20:00Z", "PENDING");
        insertBooking(hostId, "2026-07-01T10:20:00Z", "2026-07-01T10:40:00Z", "PENDING");
        insertBooking(hostId, "2026-07-01T10:40:00Z", "2026-07-01T11:00:00Z", "PENDING");

        Instant start = Instant.parse("2026-07-01T10:00:00Z");
        Instant end   = Instant.parse("2026-07-01T11:00:00Z");

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<ErrorCode>> futures = new ArrayList<>(threads);

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                try {
                    bookingService.createBooking(hostId, eventTypeId, start, end);
                    return null; // unexpected success — assertion below will catch this
                } catch (CustomException ex) {
                    return ex.getErrorCode();
                }
            }));
        }

        startGate.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            fail("Thread pool did not terminate in 30 s — possible deadlock");
        }

        for (Future<ErrorCode> f : futures) {
            ErrorCode code = f.get(); // re-throws on unexpected crash
            assertNotNull(code, "createBooking must fail when pending limit is already reached");
            assertEquals(ErrorCode.TOO_MANY_PENDING_BOOKINGS, code,
                    "expected TOO_MANY_PENDING_BOOKINGS, got " + code);
        }

        // No new rows — only the MAX_PENDING pre-seeded bookings remain.
        assertEquals(MAX_PENDING,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM bookings WHERE host_id = ?",
                        Integer.class, hostId));
    }

    // ── Test 2 ───────────────────────────────────────────────────────────────

    // CONFIRMED bookings do not count toward the PENDING limit. Pre-seeding
    // MAX_PENDING + 1 CONFIRMED bookings leaves the PENDING count at zero, so the
    // limit check passes. The EXCLUDE constraint then fires (CONFIRMED is in its
    // WHERE clause), producing SLOT_ALREADY_BOOKED rather than
    // TOO_MANY_PENDING_BOOKINGS — proving the limit was never triggered.
    @Test
    void pendingLimit_doesNotBlockConfirmed() {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();

        insertBooking(hostId, "2026-07-01T10:00:00Z", "2026-07-01T10:20:00Z", "CONFIRMED");
        insertBooking(hostId, "2026-07-01T10:20:00Z", "2026-07-01T10:40:00Z", "CONFIRMED");
        insertBooking(hostId, "2026-07-01T10:40:00Z", "2026-07-01T11:00:00Z", "CONFIRMED");
        insertBooking(hostId, "2026-07-01T11:00:00Z", "2026-07-01T11:20:00Z", "CONFIRMED");

        CustomException ex = assertThrows(CustomException.class, () ->
                bookingService.createBooking(hostId, eventTypeId,
                        Instant.parse("2026-07-01T10:00:00Z"),
                        Instant.parse("2026-07-01T10:20:00Z")));

        // Must be SLOT_ALREADY_BOOKED, not TOO_MANY_PENDING_BOOKINGS: the limit
        // was not triggered (PENDING count = 0); the EXCLUDE constraint fired.
        assertEquals(ErrorCode.SLOT_ALREADY_BOOKED, ex.getErrorCode(),
                "CONFIRMED bookings must not trigger the pending limit");
    }

    // ── Test 3 ───────────────────────────────────────────────────────────────

    // CANCELLED bookings do not count toward the PENDING limit, and the EXCLUDE
    // constraint ignores them (WHERE status IN ('PENDING','CONFIRMED')).
    // Pre-seeding MAX_PENDING + 1 CANCELLED bookings leaves both the pending
    // count and the EXCLUDE scope at zero, so a new booking for the same window
    // must succeed.
    @Test
    void cancelledBookings_doNotCount() {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();

        insertBooking(hostId, "2026-07-01T10:00:00Z", "2026-07-01T10:20:00Z", "CANCELLED");
        insertBooking(hostId, "2026-07-01T10:20:00Z", "2026-07-01T10:40:00Z", "CANCELLED");
        insertBooking(hostId, "2026-07-01T10:40:00Z", "2026-07-01T11:00:00Z", "CANCELLED");
        insertBooking(hostId, "2026-07-01T11:00:00Z", "2026-07-01T11:20:00Z", "CANCELLED");

        assertDoesNotThrow(() ->
                bookingService.createBooking(hostId, eventTypeId,
                        Instant.parse("2026-07-01T10:00:00Z"),
                        Instant.parse("2026-07-01T11:20:00Z")));

        assertEquals(1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM bookings WHERE host_id = ? AND status = 'PENDING'",
                        Integer.class, hostId),
                "exactly one new PENDING booking must exist");
    }

    // ── Test 4 ───────────────────────────────────────────────────────────────

    // The pending limit is scoped per host_id. Filling host A's window to
    // MAX_PENDING must not consume any of host B's quota for the same time range.
    @Test
    void differentHosts_notAffected() {
        UUID hostA       = createHost().getId();
        UUID hostB       = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();

        insertBooking(hostA, "2026-07-01T10:00:00Z", "2026-07-01T10:20:00Z", "PENDING");
        insertBooking(hostA, "2026-07-01T10:20:00Z", "2026-07-01T10:40:00Z", "PENDING");
        insertBooking(hostA, "2026-07-01T10:40:00Z", "2026-07-01T11:00:00Z", "PENDING");

        assertDoesNotThrow(() ->
                bookingService.createBooking(hostB, eventTypeId,
                        Instant.parse("2026-07-01T10:00:00Z"),
                        Instant.parse("2026-07-01T11:00:00Z")),
                "host B must not be affected by host A's pending bookings");

        assertEquals(1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM bookings WHERE host_id = ?",
                        Integer.class, hostB),
                "host B must have exactly one booking");
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private void insertBooking(UUID hostId, String start, String end, String status) {
        jdbc.update("""
                INSERT INTO bookings
                    (id, host_id, event_type_id, start_time, end_time, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), hostId, UUID.randomUUID(),
                Timestamp.from(Instant.parse(start)),
                Timestamp.from(Instant.parse(end)),
                status);
    }
}
