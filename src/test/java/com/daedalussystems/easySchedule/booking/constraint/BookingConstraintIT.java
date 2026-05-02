package com.daedalussystems.easySchedule.booking.constraint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.daedalussystems.easySchedule.booking.AbstractBookingIT;
import com.daedalussystems.easySchedule.booking.service.BookingService;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Service-layer integration tests for booking overlap invariants.
//
// These tests operate through the real BookingService against a real PostgreSQL
// instance; no mocks. They validate the full enforcement chain:
//
//   1. SELECT FOR UPDATE on the host user row  — serialises concurrent requests
//   2. Application-level overlap pre-check     — fast path (scheduled for removal)
//   3. EXCLUDE USING gist on each partition    — authoritative DB-level enforcer
//
// The DB-level contract is proven independently in BookingOverlapConstraintIT
// (pure JDBC, no Spring context). These tests prove end-to-end service behaviour
// under real concurrency.
@Testcontainers(disabledWithoutDocker = true)
class BookingConstraintIT extends AbstractBookingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host",     redis::getHost);
        registry.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
    }

    @Autowired
    private BookingService bookingService;

    // ── Scenario 1 ────────────────────────────────────────────────────────────

    // 20 threads race to book the same host in the same time slot.
    // The SELECT FOR UPDATE on the host row serialises them; the application
    // pre-check (and behind it the EXCLUDE constraint) ensures only one succeeds.
    // All failures must be SLOT_ALREADY_BOOKED — no unexpected exceptions.
    @Test
    void concurrent20OverlappingBookings_exactlyOneSucceeds() throws Exception {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();
        Instant start    = Instant.parse("2026-06-01T10:00:00Z");
        Instant end      = Instant.parse("2026-06-01T10:30:00Z");

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>(threads);

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                try {
                    bookingService.createBooking(hostId, eventTypeId, start, end);
                    return true;  // success
                } catch (CustomException ex) {
                    assertEquals(ErrorCode.SLOT_ALREADY_BOOKED, ex.getErrorCode(),
                            "failed booking must report SLOT_ALREADY_BOOKED, not another error");
                    return false; // expected conflict
                }
                // Any other exception propagates and fails the Future, which
                // causes f.get() below to throw — failing the test explicitly.
            }));
        }

        startGate.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            fail("Thread pool did not terminate in 30 s — possible deadlock");
        }

        long successes = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) successes++; // f.get() re-throws on unexpected crash
        }

        assertEquals(1, successes, "exactly one booking must succeed");
        assertEquals(1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM bookings WHERE host_id = ?",
                        Integer.class, hostId),
                "exactly one booking row must exist in DB");
    }

    // ── Scenario 2 ────────────────────────────────────────────────────────────

    // Non-overlapping bookings for the same host all succeed.
    // Adjacent slots share an endpoint but tstzrange uses [start, end) semantics
    // so they are considered disjoint — the EXCLUDE constraint must not fire.
    @Test
    void nonOverlappingBookings_allSucceed() {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();
        int  slots       = 5;

        for (int i = 0; i < slots; i++) {
            Instant start = Instant.parse("2026-06-01T09:00:00Z").plusSeconds(i * 1800L);
            Instant end   = start.plusSeconds(1800);
            bookingService.createBooking(hostId, eventTypeId, start, end);
        }

        assertEquals(slots,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM bookings WHERE host_id = ?",
                        Integer.class, hostId),
                "all non-overlapping bookings must persist");
    }

    // ── Scenario 3 ────────────────────────────────────────────────────────────

    // 20 threads each book a DIFFERENT host in the same time slot concurrently.
    // Different hosts never conflict — all 20 bookings must succeed.
    // Any failure is treated as a test bug (fail loudly, not silently).
    @Test
    void concurrent20DifferentHosts_allSucceed() throws Exception {
        int threads = 20;
        List<UUID> hostIds = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            hostIds.add(createHost().getId());
        }

        UUID eventTypeId = UUID.randomUUID();
        Instant start    = Instant.parse("2026-06-01T10:00:00Z");
        Instant end      = Instant.parse("2026-06-01T10:30:00Z");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>(threads);

        for (UUID hostId : hostIds) {
            futures.add(pool.submit(() -> {
                startGate.await();
                // Should never throw — different hosts do not conflict.
                bookingService.createBooking(hostId, eventTypeId, start, end);
                return hostId;
            }));
        }

        startGate.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            fail("Thread pool did not terminate in 30 s");
        }

        // f.get() re-throws any exception from the thread — fails the test loudly
        // if any booking unexpectedly failed.
        for (Future<UUID> f : futures) {
            f.get();
        }

        // Verify globally
        assertEquals(threads,
                jdbc.queryForObject("SELECT COUNT(*) FROM bookings", Integer.class),
                "all different-host bookings must exist");

        // Verify per host — each host must have exactly one booking
        for (UUID hostId : hostIds) {
            assertEquals(1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM bookings WHERE host_id = ?",
                            Integer.class, hostId),
                    "host " + hostId + " must have exactly one booking");
        }
    }

    // ── Scenario 4 ────────────────────────────────────────────────────────────

    // DOCUMENTS A KNOWN ISSUE in the application-level pre-check:
    // findByHostIdAndStartTimeLessThanAndEndTimeGreaterThan() does not filter by
    // status, so a CANCELLED booking incorrectly blocks rebooking of the same
    // slot at the service layer.
    //
    // At the DB level, the EXCLUDE constraint correctly allows it because its
    // WHERE clause excludes CANCELLED rows. This is proven by
    // BookingOverlapConstraintIT.cancelledThenPending_samSlot_succeed().
    //
    // Remediation: once the pre-check is removed (marked "scheduled for removal"
    // in BookingService), update this test to assert success instead.
    @Test
    void cancelledBooking_applicationPreCheckBlocksRebooking_knownIssue() {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();
        Instant start    = Instant.parse("2026-06-01T11:00:00Z");
        Instant end      = Instant.parse("2026-06-01T11:30:00Z");

        bookingService.createBooking(hostId, eventTypeId, start, end);
        // Cancel directly — status is not in the Booking entity so we use JDBC.
        jdbc.update("UPDATE bookings SET status = 'CANCELLED' WHERE host_id = ?", hostId);

        // Pre-check returns the cancelled booking (no status filter) → wrong rejection.
        // Correct behaviour (DB-level) would allow this. See BookingOverlapConstraintIT.
        CustomException ex = assertThrows(CustomException.class,
                () -> bookingService.createBooking(hostId, eventTypeId, start, end));
        assertEquals(ErrorCode.SLOT_ALREADY_BOOKED, ex.getErrorCode(),
                "Known pre-check bug: CANCELLED booking should not block rebooking; "
                        + "DB EXCLUDE constraint allows it — see BookingOverlapConstraintIT");
    }
}
