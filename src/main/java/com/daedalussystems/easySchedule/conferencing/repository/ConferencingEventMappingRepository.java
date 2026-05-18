package com.daedalussystems.easySchedule.conferencing.repository;

import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import com.daedalussystems.easySchedule.conferencing.domain.ConferencingEventMapping;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConferencingEventMappingRepository extends JpaRepository<ConferencingEventMapping, UUID> {
    Optional<ConferencingEventMapping> findByBookingIdAndProvider(UUID bookingId, ConferencingProviderType provider);
}
