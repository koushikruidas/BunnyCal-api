package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.*;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class BookingStressIT extends AbstractBookingIT {

    @Autowired
    private BookingService bookingService;

    // ─────────────────────────────────────────────────────────────
    // 100 concurrent requests, same host, same slot.
    //
    // Correctness guarantee: the DB EXCLUDE constraint is the true
    // authority. All N threads race to INSERT; the DB accepts exactly
    // one and rejects the rest with a constraint violation, which the
    // service translates to SLOT_ALREADY_BOOKED.
    //
    // findByIdForUpdate (FOR UPDATE on the host row) reduces contention
    // chaos and serializes the lock waiters, but it is NOT the safety
    // guarantee. Even without it the EXCLUDE constraint would still
    // produce exactly one winner — FOR UPDATE is an optimisation, not
    // a correctness invariant.
    //
    // Thread pool of 32 simulates a realistic server thread pool and
    // maximises interleaving among the 100 tasks.
    // ─────────────────────────────────────────────────────────────
    @Test
    void concurrentRequests_onlyOneBookingSucceeds() throws Exception {
        final int N = 100;

        User host = createHost();
        UUID eventTypeId = UUID.randomUUID();
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant end   = start.plus(30, ChronoUnit.MINUTES);

        AtomicInteger successCount   = new AtomicInteger(0);
        AtomicInteger failureCount   = new AtomicInteger(0);
        List<ErrorCode> failureCodes     = new CopyOnWriteArrayList<>();
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);

        List<CompletableFuture<Void>> futures = IntStream.range(0, N)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try { startGun.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                    try {
                        bookingService.createBooking(host.getId(), eventTypeId, start, end);
                        successCount.incrementAndGet();
                    } catch (CustomException e) {
                        failureCount.incrementAndGet();
                        failureCodes.add(e.getErrorCode());
                    } catch (Exception e) {
                        unexpectedErrors.add(e);
                        failureCount.incrementAndGet();
                    }
                }, pool))
                .toList();

        startGun.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();

        // ── Core invariants ────────────────────────────────────────
        assertTrue(unexpectedErrors.isEmpty(),
                "unexpected (non-domain) errors: " + unexpectedErrors);

        assertEquals(1, successCount.get(),
                "exactly one booking must succeed");
        assertEquals(N - 1, failureCount.get(),
                "all other attempts must fail");

        // The EXCLUDE constraint prevents more than one PENDING booking
        // per slot, so TOO_MANY_PENDING_BOOKINGS cannot be reached here.
        assertTrue(
                failureCodes.stream().allMatch(c -> c == ErrorCode.SLOT_ALREADY_BOOKED),
                "all failures must be SLOT_ALREADY_BOOKED, got: " + failureCodes);

        // ── DB state ───────────────────────────────────────────────
        assertEquals(1,
                (int) jdbc.queryForObject(
                        "SELECT COUNT(*) FROM bookings WHERE host_id = ? AND start_time = ? AND end_time = ?",
                        Integer.class,
                        host.getId(), Timestamp.from(start), Timestamp.from(end)),
                "exactly one booking must exist in DB for this slot");

        assertEquals(0,
                (int) jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM bookings WHERE host_id = ?
                        AND status NOT IN
                            ('PENDING','CONFIRMED','CANCELLED','EXPIRED','COMPLETED','REJECTED')
                        """,
                        Integer.class, host.getId()),
                "no booking must be in an invalid state");

        // status and version are intentionally not mapped in the Booking entity;
        // the DB DEFAULT sets both at INSERT time.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, version FROM bookings WHERE host_id = ? AND start_time = ? AND end_time = ?",
                host.getId(), Timestamp.from(start), Timestamp.from(end));
        assertEquals("PENDING", row.get("status"),
                "winning booking must be in PENDING state");
        assertEquals(0L, ((Number) row.get("version")).longValue(),
                "fresh booking must have version = 0");

        // Exactly one outbox event (status may be PROCESSED if the background
        // OutboxWorker fired during the test — count is stable either way)
        assertEquals(1,
                (int) jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'Booking' AND event_type = 'BOOKING_CREATED'",
                        Integer.class),
                "exactly one outbox event must exist for the winning booking");
    }
}
