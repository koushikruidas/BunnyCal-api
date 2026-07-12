package io.bunnycal.booking.outbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Idempotency guard: claim an event for dispatch.
     *
     * <p>Returns 1 when this caller won the claim (the row was inserted), 0 when the event was
     * already dispatched (PK conflict, silently ignored) — which is what makes a crash-and-recover
     * cycle skip the send instead of repeating it.
     *
     * <p>The claim <b>commits before the dispatch runs</b>, because the dispatch performs external
     * network calls (SMTP, provider HTTP) and must not hold a pooled DB connection while it does.
     * It therefore cannot rely on a rollback to undo itself: a failed dispatch has to call
     * {@link #release} explicitly, or the event stays marked processed and never retries.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_events (event_id, processed_at)
            VALUES (:eventId, :processedAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int tryInsert(@Param("eventId") UUID eventId, @Param("processedAt") Instant processedAt);

    /**
     * Releases a claim taken by {@link #tryInsert} when the dispatch failed, so the event is
     * eligible to be retried. Without this the guard row — committed before the dispatch — would
     * make every subsequent attempt skip the send silently.
     */
    @Modifying
    @Query(value = "DELETE FROM processed_events WHERE event_id = :eventId", nativeQuery = true)
    int release(@Param("eventId") UUID eventId);

    /**
     * Crash recovery: releases the claims of events the reaper is about to reset from PROCESSING
     * back to PENDING.
     *
     * <p>A worker that dies between committing its claim and completing the dispatch leaves a guard
     * row for a dispatch that never happened. The reaper makes the event runnable again, but
     * without this the reclaimed event would hit the guard, {@code tryInsert} would return 0, and
     * the dispatch would be skipped forever — a silently lost notification. Scoped to rows the
     * reaper is actually recovering (stuck in PROCESSING past the cutoff, attempts remaining), so
     * it can never clear the guard of an event that genuinely was dispatched.
     */
    @Modifying
    @Query(value = """
            DELETE FROM processed_events pe
            USING outbox_events oe
            WHERE pe.event_id = oe.id
              AND oe.status = 'PROCESSING'
              AND oe.updated_at < :cutoff
              AND oe.attempt_count < :maxAttempts
            """, nativeQuery = true)
    int releaseStuckClaims(@Param("cutoff") Instant cutoff, @Param("maxAttempts") int maxAttempts);
}
