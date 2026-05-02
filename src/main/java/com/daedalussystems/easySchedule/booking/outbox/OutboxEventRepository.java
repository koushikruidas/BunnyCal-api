package com.daedalussystems.easySchedule.booking.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    // Phase-1 claim, step 1: identify rows to claim.
    // FOR UPDATE SKIP LOCKED prevents concurrent workers from selecting
    // the same rows simultaneously. Rows already locked by another worker
    // are skipped entirely rather than blocking.
    // Must be called inside an active transaction (provided by OutboxWorker
    // via TransactionTemplate with PROPAGATION_REQUIRES_NEW).
    @Query(value = """
            SELECT id FROM outbox_events
            WHERE status = 'PENDING'
              AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> claimPendingIds(@Param("now") Instant now, @Param("batchSize") int batchSize);

    // Phase-1 claim, step 2: atomically mark the claimed rows PROCESSING.
    // clearAutomatically = true invalidates the L1 cache so a subsequent
    // findById in the same TX sees the updated status.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = :processing, e.nextAttemptAt = :now WHERE e.id IN :ids")
    void markAsProcessing(
            @Param("ids") List<UUID> ids,
            @Param("processing") OutboxEventStatus processing,
            @Param("now") Instant now);

    // Reaper step 1: reset orphaned PROCESSING rows to PENDING so a future
    // worker can re-attempt them.
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = :pending, e.nextAttemptAt = :now
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

    // Reaper step 2: permanently fail exhausted PROCESSING rows whose worker
    // died after max attempts were already spent.
    @Modifying(clearAutomatically = true)
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
}
