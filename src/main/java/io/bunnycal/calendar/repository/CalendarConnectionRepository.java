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
}
