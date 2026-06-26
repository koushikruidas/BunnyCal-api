package io.bunnycal.payments.webhook;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
