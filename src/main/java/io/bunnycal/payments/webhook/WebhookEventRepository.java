package io.bunnycal.payments.webhook;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WebhookEventRepository
        extends JpaRepository<WebhookEvent, UUID>, JpaSpecificationExecutor<WebhookEvent> {

    Optional<WebhookEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
