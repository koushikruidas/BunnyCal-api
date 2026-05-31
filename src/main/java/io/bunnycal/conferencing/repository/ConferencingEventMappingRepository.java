package io.bunnycal.conferencing.repository;

import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.domain.ConferencingEventMapping;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConferencingEventMappingRepository extends JpaRepository<ConferencingEventMapping, UUID> {
    Optional<ConferencingEventMapping> findByBookingIdAndProvider(UUID bookingId, ConferencingProviderType provider);
}
