package io.bunnycal.hostpayments.repository;

import io.bunnycal.hostpayments.domain.EventPaymentConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventPaymentConfigRepository extends JpaRepository<EventPaymentConfig, UUID> {
    Optional<EventPaymentConfig> findByEventTypeIdAndEnabledTrue(UUID eventTypeId);
}
