package io.bunnycal.payments.webhook;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository
        extends JpaRepository<WebhookEvent, UUID>, JpaSpecificationExecutor<WebhookEvent> {

    interface WebhookSearchRow {
        UUID getId();
        String getProvider();
        String getProviderEventId();
        String getType();
        String getStatus();
        java.time.Instant getReceivedAt();
    }

    Optional<WebhookEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    @Query(value = """
            SELECT id,
                   provider,
                   provider_event_id AS providerEventId,
                   type,
                   status,
                   received_at AS receivedAt
            FROM webhook_events
            WHERE CAST(id AS text) = :exact
               OR lower(provider_event_id) LIKE :pattern
               OR lower(type) LIKE :pattern
               OR lower(provider) LIKE :pattern
            ORDER BY received_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    java.util.List<WebhookSearchRow> searchAdmin(
            @Param("exact") String exact,
            @Param("pattern") String pattern,
            @Param("limit") int limit);

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);

    long countByStatus(WebhookEventStatus status);
}
