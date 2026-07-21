package io.bunnycal.hostpayments.repository;

import io.bunnycal.hostpayments.domain.HostCommerceWebhookEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HostCommerceWebhookEventRepository extends JpaRepository<HostCommerceWebhookEvent, UUID> {
    Optional<HostCommerceWebhookEvent> findByProviderAndProviderAccountIdAndProviderEventId(
            String provider, String providerAccountId, String providerEventId);
}
