package io.bunnycal.calendar.repository;

import io.bunnycal.calendar.domain.CalendarProviderOperation;
import io.bunnycal.calendar.domain.CalendarProviderType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarProviderOperationRepository extends JpaRepository<CalendarProviderOperation, java.util.UUID> {
    Optional<CalendarProviderOperation> findByProviderAndIdempotencyKey(CalendarProviderType provider, String idempotencyKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO calendar_provider_operations (
                id, provider, connection_id, idempotency_key, status, last_attempt_at, created_at, updated_at
            ) VALUES (
                :id, :provider, :connectionId, :idempotencyKey, :status, :lastAttemptAt, NOW(), NOW()
            )
            ON CONFLICT (provider, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertCreatingIfAbsent(@Param("id") UUID id,
                               @Param("provider") String provider,
                               @Param("connectionId") UUID connectionId,
                               @Param("idempotencyKey") String idempotencyKey,
                               @Param("status") String status,
                               @Param("lastAttemptAt") Instant lastAttemptAt);
}
