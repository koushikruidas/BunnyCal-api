package com.daedalussystems.easySchedule.availability.repository;

import com.daedalussystems.easySchedule.availability.domain.EventType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventTypeRepository extends JpaRepository<EventType, UUID> {

    Optional<EventType> findByIdAndUserId(UUID id, UUID userId);
    Optional<EventType> findByUserIdAndSlug(UUID userId, String slug);
    List<EventType> findByUserIdOrderByNameAsc(UUID userId);
    boolean existsByUserIdAndSlug(UUID userId, String slug);
}
