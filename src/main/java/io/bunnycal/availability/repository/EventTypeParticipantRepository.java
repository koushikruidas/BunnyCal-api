package io.bunnycal.availability.repository;

import io.bunnycal.availability.domain.EventTypeParticipant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface EventTypeParticipantRepository extends JpaRepository<EventTypeParticipant, UUID> {

    List<EventTypeParticipant> findByEventTypeIdOrderByDisplayOrderAscCreatedAtAsc(UUID eventTypeId);

    @Transactional
    void deleteByEventTypeId(UUID eventTypeId);
}
