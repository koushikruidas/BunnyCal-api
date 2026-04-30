package com.daedalussystems.easySchedule.availability.repository;

import com.daedalussystems.easySchedule.availability.domain.AvailabilityOverride;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvailabilityOverrideRepository extends JpaRepository<AvailabilityOverride, UUID> {

    boolean existsByUserIdAndDate(UUID userId, LocalDate date);

    List<AvailabilityOverride> findByUserIdAndDateBetweenOrderByDateAsc(UUID userId, LocalDate from, LocalDate to);

    Optional<AvailabilityOverride> findByIdAndUserId(UUID id, UUID userId);
}
