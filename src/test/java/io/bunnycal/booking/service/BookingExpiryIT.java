package io.bunnycal.booking.service;

import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class BookingExpiryIT extends AbstractBookingIT {

    @Autowired BookingService bookingService;

    private UUID hostId;
    private UUID eventTypeId;
    private Instant start;
    private Instant end;
    private static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");

    @BeforeEach
    void setup() {
        User host = createHost();
        hostId = host.getId();
        eventTypeId = UUID.randomUUID();
        start = Instant.parse("2030-01-01T10:00:00Z");
        end   = Instant.parse("2030-01-01T11:00:00Z");
    }

    @Test
    void expiry_succeeds_forPendingExpiredBooking() {
        UUID id = insertBooking(hostId, eventTypeId, start, end, "PENDING", 0, PAST);

        bookingService.expireBooking(id, 0);

        Map<String, Object> row = queryBookingRow(id);
        assertEquals("EXPIRED", row.get("status"));
        assertEquals(1L, row.get("version"));
    }

    @Test
    void expiry_fails_forNonPendingBooking() {
        // CONFIRMED, CANCELLED, COMPLETED all fail — status != PENDING in the CAS WHERE clause
        UUID confirmed  = insertBooking(hostId, eventTypeId, start, end, "CONFIRMED",  0, PAST);
        UUID cancelled  = insertBooking(hostId, eventTypeId, start, end, "CANCELLED",  0, PAST);

        for (UUID id : List.of(confirmed, cancelled)) {
            CustomException ex = assertThrows(CustomException.class,
                    () -> bookingService.expireBooking(id, 0));
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
        }
    }

    @Test
    void expiry_fails_forStaleVersion() {
        UUID id = insertBooking(hostId, eventTypeId, start, end, "PENDING", 5, PAST);

        // Caller believes version is 0; DB has version 5 → CAS miss
        CustomException ex = assertThrows(CustomException.class,
                () -> bookingService.expireBooking(id, 0));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());

        Map<String, Object> row = queryBookingRow(id);
        assertEquals("PENDING", row.get("status"));
        assertEquals(5L, row.get("version"));
    }

    @Test
    void race_confirmVsExpiry_exactlyOneWins() throws Exception {
        // Booking is PENDING and already past its TTL — both actions compete at version 0.
        UUID id = insertBooking(hostId, eventTypeId, start, end, "PENDING", 0, PAST);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        ExecutorService pool = Executors.newCachedThreadPool();

        CompletableFuture<Void> confirm = CompletableFuture.runAsync(() -> {
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

        CompletableFuture<Void> expire = CompletableFuture.runAsync(() -> {
            try {
                latch.await();
                bookingService.expireBooking(id, 0);
                successCount.incrementAndGet();
            } catch (CustomException e) {
                synchronized (failures) { failures.add(e); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, pool);

        latch.countDown();
        CompletableFuture.allOf(confirm, expire).join();
        pool.shutdown();

        assertEquals(1, successCount.get());
        assertEquals(1, failures.size());
        CustomException failure = (CustomException) failures.get(0);
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, failure.getErrorCode());

        Map<String, Object> row = queryBookingRow(id);
        assertTrue(Set.of("CONFIRMED", "EXPIRED").contains(row.get("status")));
        assertEquals(1L, row.get("version"));
    }

    @Test
    void expiry_doesNotOverride_confirmedBooking() {
        UUID id = insertBooking(hostId, eventTypeId, start, end, "PENDING", 0, PAST);

        // Confirm wins first (version 0 → 1)
        bookingService.confirmBooking(id, 0);

        // Expiry with stale version=0 must fail — booking is now CONFIRMED at version 1
        CustomException ex = assertThrows(CustomException.class,
                () -> bookingService.expireBooking(id, 0));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());

        Map<String, Object> row = queryBookingRow(id);
        assertEquals("CONFIRMED", row.get("status"));
        assertEquals(1L, row.get("version"));
    }
}
