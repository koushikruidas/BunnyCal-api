package com.daedalussystems.easySchedule.booking.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daedalussystems.easySchedule.booking.AbstractBookingIT;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.ClaimOutcome;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.FinalizeOutcome;
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

@Testcontainers(disabledWithoutDocker = true)
class CalendarEventMappingRepositoryIT extends AbstractBookingIT {

    @Autowired
    private CalendarEventMappingRepository repository;

    @Test
    void concurrentClaims_sameToken_onlyOneClaims() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";
        long token = 10L;

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ClaimOutcome>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String workerId = "worker-" + i;
            futures.add(pool.submit(() -> {
                gate.await();
                return repository.claimBookingForSync(bookingId, provider, token, workerId);
            }));
        }

        gate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        int claimed = 0;
        int rejected = 0;
        for (Future<ClaimOutcome> f : futures) {
            ClaimOutcome outcome = f.get();
            if (outcome == ClaimOutcome.CLAIMED) claimed++;
            if (outcome == ClaimOutcome.REJECTED) rejected++;
        }

        assertEquals(1, claimed);
        assertEquals(threads - 1, rejected);
        assertEquals("CLAIMED", jdbc.queryForObject(
                "SELECT status FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                String.class, bookingId, provider));
        assertEquals(token, jdbc.queryForObject(
                "SELECT sync_token FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                Long.class, bookingId, provider));
    }

    @Test
    void higherToken_takeover_winsOwnership() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";

        assertEquals(ClaimOutcome.CLAIMED,
                repository.claimBookingForSync(bookingId, provider, 5L, "worker-a"));
        assertEquals(ClaimOutcome.CLAIMED,
                repository.claimBookingForSync(bookingId, provider, 6L, "worker-b"));

        assertEquals("worker-b", jdbc.queryForObject(
                "SELECT claimed_by FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                String.class, bookingId, provider));
        assertEquals("CLAIMED", jdbc.queryForObject(
                "SELECT status FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                String.class, bookingId, provider));
        assertEquals(6L, jdbc.queryForObject(
                "SELECT sync_token FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                Long.class, bookingId, provider));
    }

    @Test
    void staleOrEqualToken_isRejected() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";

        assertEquals(ClaimOutcome.CLAIMED,
                repository.claimBookingForSync(bookingId, provider, 11L, "worker-a"));
        assertEquals(ClaimOutcome.REJECTED,
                repository.claimBookingForSync(bookingId, provider, 11L, "worker-b"));
        assertEquals(ClaimOutcome.REJECTED,
                repository.claimBookingForSync(bookingId, provider, 10L, "worker-c"));
    }

    @Test
    void createdRow_returnsAlreadyDone() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";
        Instant now = Instant.now();

        jdbc.update("""
                INSERT INTO calendar_event_mappings
                    (booking_id, provider, sync_token, status, external_event_id, claimed_by, claimed_at, created_at, updated_at)
                VALUES (?, ?, ?, 'CREATED', ?, ?, ?, ?, ?)
                """,
                bookingId, provider, 50L, "external-123", "worker-a",
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        assertEquals(ClaimOutcome.ALREADY_DONE,
                repository.claimBookingForSync(bookingId, provider, 51L, "worker-b"));
    }

    @Test
    void failedToClaimed_takeover_clearsFailureResidue() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";
        Instant now = Instant.now();

        jdbc.update("""
                INSERT INTO calendar_event_mappings
                    (booking_id, provider, sync_token, status, last_error, claimed_by, claimed_at, created_at, updated_at)
                VALUES (?, ?, ?, 'FAILED', ?, ?, ?, ?, ?)
                """,
                bookingId, provider, 20L, "timeout", "worker-a",
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        assertEquals(ClaimOutcome.CLAIMED,
                repository.claimBookingForSync(bookingId, provider, 21L, "worker-b"));

        assertEquals("CLAIMED", jdbc.queryForObject(
                "SELECT status FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                String.class, bookingId, provider));
        assertEquals("worker-b", jdbc.queryForObject(
                "SELECT claimed_by FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                String.class, bookingId, provider));
        assertNull(jdbc.queryForObject(
                "SELECT last_error FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                String.class, bookingId, provider));
        assertNull(jdbc.queryForObject(
                "SELECT external_event_id FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                String.class, bookingId, provider));
    }

    @Test
    void finalize_success_claimedToCreated() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";
        long token = 100L;
        String worker = "worker-a";

        assertEquals(ClaimOutcome.CLAIMED,
                repository.claimBookingForSync(bookingId, provider, token, worker));

        assertEquals(FinalizeOutcome.SUCCESS,
                repository.updateMappingWithEventId(
                        bookingId, provider, "ext-1", token, worker));

        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                String.class, bookingId, provider));
        assertEquals("ext-1", jdbc.queryForObject(
                "SELECT external_event_id FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                String.class, bookingId, provider));
        assertEquals("worker-a", jdbc.queryForObject(
                "SELECT claimed_by FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                String.class, bookingId, provider));
        assertEquals(token, jdbc.queryForObject(
                "SELECT sync_token FROM calendar_event_mappings WHERE booking_id = ? AND provider = ?",
                Long.class, bookingId, provider));
    }

    @Test
    void finalize_idempotentRetry_sameExternalId_isAlreadyCompleted() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";
        Instant now = Instant.now();

        jdbc.update("""
                INSERT INTO calendar_event_mappings
                    (booking_id, provider, sync_token, status, external_event_id, claimed_by, claimed_at, created_at, updated_at)
                VALUES (?, ?, ?, 'CREATED', ?, ?, ?, ?, ?)
                """,
                bookingId, provider, 77L, "ext-same", "worker-a",
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        assertEquals(FinalizeOutcome.ALREADY_COMPLETED,
                repository.updateMappingWithEventId(
                        bookingId, provider, "ext-same", 77L, "worker-a"));
    }

    @Test
    void finalize_splitBrain_differentExternalId_isDetected() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";
        Instant now = Instant.now();

        jdbc.update("""
                INSERT INTO calendar_event_mappings
                    (booking_id, provider, sync_token, status, external_event_id, claimed_by, claimed_at, created_at, updated_at)
                VALUES (?, ?, ?, 'CREATED', ?, ?, ?, ?, ?)
                """,
                bookingId, provider, 88L, "ext-original", "worker-a",
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        assertEquals(FinalizeOutcome.SPLIT_BRAIN_DETECTED,
                repository.updateMappingWithEventId(
                        bookingId, provider, "ext-different", 88L, "worker-a"));
    }

    @Test
    void finalize_staleToken_isRejected() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";

        assertEquals(ClaimOutcome.CLAIMED,
                repository.claimBookingForSync(bookingId, provider, 42L, "worker-a"));

        assertEquals(FinalizeOutcome.STALE_OR_NOT_OWNER,
                repository.updateMappingWithEventId(
                        bookingId, provider, "ext-1", 41L, "worker-a"));
    }

    @Test
    void finalize_ownerMismatch_isRejected() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";

        assertEquals(ClaimOutcome.CLAIMED,
                repository.claimBookingForSync(bookingId, provider, 200L, "worker-a"));

        assertEquals(FinalizeOutcome.STALE_OR_NOT_OWNER,
                repository.updateMappingWithEventId(
                        bookingId, provider, "ext-1", 200L, "worker-b"));
    }
}
