package io.bunnycal.calendar.repository;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, UUID> {
    Optional<CalendarConnection> findByUserIdAndProvider(UUID userId, CalendarProviderType provider);

    Optional<CalendarConnection> findByUserIdAndProviderAndStatus(UUID userId,
                                                                  CalendarProviderType provider,
                                                                  CalendarConnectionStatus status);
    java.util.List<CalendarConnection> findByUserIdAndStatus(UUID userId, CalendarConnectionStatus status);
    java.util.List<CalendarConnection> findByStatus(CalendarConnectionStatus status);
    java.util.List<CalendarConnection> findByProviderAndWebhookChannelExpiresAtBefore(
            CalendarProviderType provider,
            Instant expiresAt);
    java.util.List<CalendarConnection> findByProviderAndWebhookChannelExpiresAtIsNotNull(CalendarProviderType provider);
    Optional<CalendarConnection> findByWebhookChannelId(String webhookChannelId);

    @Query(value = """
            SELECT *
            FROM calendar_connections
            WHERE :scope = ANY(scopes)
            """, nativeQuery = true)
    java.util.List<CalendarConnection> findAllByScope(@Param("scope") String scope);

    java.util.List<CalendarConnection> findByUserIdAndStatusOrderByCreatedAtAsc(UUID userId, CalendarConnectionStatus status);

    /**
     * F7: rows that are due to be swept. ACTIVE/SYNCING always; FAILED/ERROR only when
     * next_retry_at has elapsed (or is unset, which happens for legacy rows pre-migration).
     * REVOKED is intentionally excluded — reconnect is the only exit.
     */
    @Query("""
            SELECT c
            FROM CalendarConnection c
            WHERE c.status IN (
                    io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE,
                    io.bunnycal.calendar.domain.CalendarConnectionStatus.SYNCING)
               OR (c.status IN (
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.FAILED,
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.ERROR)
                   AND (c.nextRetryAt IS NULL OR c.nextRetryAt <= :now))
            """)
    java.util.List<CalendarConnection> findDueForSync(@Param("now") Instant now);
}
