package io.bunnycal.booking.service;

import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class BookingStatusTransitionIT extends AbstractBookingIT {

    @Autowired BookingService bookingService;

    private UUID hostId;
    private UUID eventTypeId;
    private Instant start;
    private Instant end;

    @BeforeEach
    void setup() {
        User host = createHost();
        hostId = host.getId();
        eventTypeId = UUID.randomUUID();
        start = Instant.parse("2030-01-01T10:00:00Z");
        end   = Instant.parse("2030-01-01T11:00:00Z");
    }

    @Test
    void validTransition_pendingToConfirmed_succeeds() {
        UUID id = insertBooking(hostId, eventTypeId, start, end, "PENDING", 0);

        bookingService.confirmBooking(id, 0);

        Map<String, Object> row = queryBookingRow(id);
        assertEquals("CONFIRMED", row.get("status"));
        assertEquals(1L, row.get("version"));
    }

    @Test
    void bookingInWrongState_throwsInvalidStateTransition() {
        UUID id = insertBooking(hostId, eventTypeId, start, end, "CANCELLED", 0);

        // confirmBooking hard-codes expectedState=PENDING; DB has CANCELLED.
        // requireAllowed(PENDING, CONFIRMED) is valid, so the static guardrail passes.
        // The CAS then misses (no row with status=PENDING) → INVALID_STATE_TRANSITION.
        CustomException ex = assertThrows(CustomException.class,
                () -> bookingService.confirmBooking(id, 0));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());

        // DB row must be untouched — CAS matched 0 rows, no write occurred
        Map<String, Object> row = queryBookingRow(id);
        assertEquals("CANCELLED", row.get("status"));
        assertEquals(0L, row.get("version"));
    }

    @Test
    void wrongExpectedState_throwsInvalidStateTransition() {
        UUID id = insertBooking(hostId, eventTypeId, start, end, "CONFIRMED", 0);

        // confirmBooking hard-codes expectedState=PENDING; DB has CONFIRMED → CAS miss
        CustomException ex = assertThrows(CustomException.class,
                () -> bookingService.confirmBooking(id, 0));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
    }

    @Test
    void concurrent_sameTransition_onlyOneSucceeds() throws Exception {
        UUID id = insertBooking(hostId, eventTypeId, start, end, "PENDING", 0);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        ExecutorService pool = Executors.newCachedThreadPool();

        CompletableFuture<Void> t1 = CompletableFuture.runAsync(() -> {
            try {
                latch.await();
                bookingService.confirmBooking(id, 0);
                successCount.incrementAndGet();
            } catch (CustomException e) {
                synchronized (failures) { failures.add(e); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, pool);

        CompletableFuture<Void> t2 = CompletableFuture.runAsync(() -> {
            try {
                latch.await();
                bookingService.confirmBooking(id, 0);
                successCount.incrementAndGet();
            } catch (CustomException e) {
                synchronized (failures) { failures.add(e); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, pool);

        latch.countDown();
        CompletableFuture.allOf(t1, t2).join();
        pool.shutdown();

        assertEquals(1, successCount.get());
        assertEquals(1, failures.size());
        CustomException failure = (CustomException) failures.get(0);
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, failure.getErrorCode());

        Map<String, Object> row = queryBookingRow(id);
        assertEquals("CONFIRMED", row.get("status"));
        assertEquals(1L, row.get("version"));
    }

    @Test
    void retryAfterSuccess_deterministicFailure() {
        UUID id = insertBooking(hostId, eventTypeId, start, end, "PENDING", 0);

        bookingService.confirmBooking(id, 0); // succeeds, version → 1

        // Same call with stale version=0 must fail
        CustomException ex = assertThrows(CustomException.class,
                () -> bookingService.confirmBooking(id, 0));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
    }

    @Test
    void terminalState_immutable_throwsInvalidStateTransition() {
        UUID id = insertBooking(hostId, eventTypeId, start, end, "CANCELLED", 0);

        // cancelPendingBooking hard-codes expectedState=PENDING; DB has CANCELLED → CAS miss.
        // expireBooking hard-codes expectedState=PENDING; DB has CANCELLED → CAS miss.
        // Both throw INVALID_STATE_TRANSITION — the DB enforces terminal-state immutability.
        CustomException ex1 = assertThrows(CustomException.class,
                () -> bookingService.cancelPendingBooking(id, 0));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex1.getErrorCode());

        CustomException ex2 = assertThrows(CustomException.class,
                () -> bookingService.expireBooking(id, 0));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex2.getErrorCode());

        // DB row must be unchanged — no writes occurred
        Map<String, Object> row = queryBookingRow(id);
        assertEquals("CANCELLED", row.get("status"));
        assertEquals(0L, row.get("version"));
    }
}
