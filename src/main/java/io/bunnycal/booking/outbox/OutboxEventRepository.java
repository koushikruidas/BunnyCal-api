package io.bunnycal.booking.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * ATOMIC CLAIM (FIXED)
     *
     * This replaces:
     *  - claimPendingIds()
     *  - markAsProcessing()
     *
     * Why:
     * - Eliminates race between SELECT and UPDATE
     * - Guarantees only one worker claims a row
     */
    @Query(value = """
        UPDATE outbox_events
        SET status = 'PROCESSING',
            updated_at = :now
        WHERE id IN (
            SELECT id FROM outbox_events
            WHERE status IN ('PENDING', 'RETRYING')
              AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        RETURNING id
        """, nativeQuery = true)
    List<UUID> claimBatch(@Param("now") Instant now,
                          @Param("batchSize") int batchSize);

    /**
     * Reaper: recover stuck PROCESSING rows
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("""
        UPDATE OutboxEvent e
        SET e.status = :pending,
            e.nextAttemptAt = :now
        WHERE e.status = :processing
          AND e.updatedAt < :cutoff
          AND e.attemptCount < :maxAttempts
    """)
    int recoverStuck(
            @Param("pending") OutboxEventStatus pending,
            @Param("processing") OutboxEventStatus processing,
            @Param("now") Instant now,
            @Param("cutoff") Instant cutoff,
            @Param("maxAttempts") int maxAttempts);

    /**
     * Reaper: permanently fail exhausted rows
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("""
        UPDATE OutboxEvent e
        SET e.status = :failed
        WHERE e.status = :processing
          AND e.updatedAt < :cutoff
          AND e.attemptCount >= :maxAttempts
    """)
    int failExhausted(
            @Param("failed") OutboxEventStatus failed,
            @Param("processing") OutboxEventStatus processing,
            @Param("cutoff") Instant cutoff,
            @Param("maxAttempts") int maxAttempts);

    @Query(value = """
        UPDATE outbox_events
        SET status = 'PROCESSING',
            updated_at = :now
        WHERE id IN (
            SELECT id FROM outbox_events
            WHERE status = 'PENDING'
              AND aggregate_type = 'Booking'
              AND event_type IN ('BOOKING_CONFIRMED', 'BOOKING_UPDATED', 'BOOKING_CANCELLED')
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        RETURNING id
        """, nativeQuery = true)
    List<UUID> claimBookingSyncEvents(@Param("now") Instant now,
                                      @Param("batchSize") int batchSize);
}
