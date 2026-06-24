package io.bunnycal.booking.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys
                (id, key, user_id, route, request_hash, status,
                 started_at, created_at, updated_at)
            VALUES (:id, :key, :userId, :route, :hash, 'IN_PROGRESS',
                    :now, :now, :now)
            ON CONFLICT ON CONSTRAINT uq_idem_scope DO NOTHING
            """, nativeQuery = true)
    int tryInsert(@Param("id") UUID id, @Param("key") String key,
                  @Param("userId") UUID userId, @Param("route") String route,
                  @Param("hash") String hash, @Param("now") Instant now);

    Optional<IdempotencyKey> findByUserIdAndRouteAndKey(UUID userId, String route, String key);

    @Modifying
    @Query("""
            UPDATE IdempotencyKey k
               SET k.status = :status, k.responseStatus = :code,
                   k.responseBody = :body, k.completedAt = :now,
                   k.updatedAt = :now
             WHERE k.userId = :userId AND k.route = :route AND k.key = :key
               AND k.status = io.bunnycal.booking.idempotency.IdempotencyStatus.IN_PROGRESS
            """)
    int finalizeByScope(@Param("userId") UUID userId, @Param("route") String route, @Param("key") String key,
                        @Param("status") IdempotencyStatus status, @Param("code") int code,
                        @Param("body") String body, @Param("now") Instant now);

    @Modifying
    @Query("""
            UPDATE IdempotencyKey k
               SET k.status = io.bunnycal.booking.idempotency.IdempotencyStatus.IN_PROGRESS,
                   k.responseStatus = null,
                   k.responseBody = null,
                   k.startedAt = :now,
                   k.completedAt = null,
                   k.updatedAt = :now
             WHERE k.userId = :userId AND k.route = :route AND k.key = :key
               AND k.status = io.bunnycal.booking.idempotency.IdempotencyStatus.FAILED
               AND k.responseStatus >= 500
            """)
    int reopenRetriableFailureByScope(@Param("userId") UUID userId,
                                      @Param("route") String route,
                                      @Param("key") String key,
                                      @Param("now") Instant now);

    @Modifying
    @Query("""
            UPDATE IdempotencyKey k
               SET k.status = io.bunnycal.booking.idempotency.IdempotencyStatus.FAILED,
                   k.responseStatus = 503,
                   k.responseBody = :abandonedBody,
                   k.completedAt = :now,
                   k.updatedAt = :now
             WHERE k.status = io.bunnycal.booking.idempotency.IdempotencyStatus.IN_PROGRESS
               AND k.updatedAt < :cutoff
               AND k.completedAt IS NULL
            """)
    int reapStuckRows(@Param("cutoff") Instant cutoff,
                      @Param("abandonedBody") String abandonedBody,
                      @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
