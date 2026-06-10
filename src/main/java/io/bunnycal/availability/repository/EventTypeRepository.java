package io.bunnycal.availability.repository;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventTypeRepository extends JpaRepository<EventType, UUID> {

    Optional<EventType> findByIdAndUserId(UUID id, UUID userId);
    Optional<EventType> findByUserIdAndSlug(UUID userId, String slug);
    List<EventType> findByUserIdOrderByNameAsc(UUID userId);
    boolean existsByUserIdAndSlug(UUID userId, String slug);

    /**
     * Returns all published COLLECTIVE event types that have {@code userId} as a participant.
     * Used by the readiness enforcer to find affected events when a participant's state changes.
     */
    @Query("""
            SELECT et
            FROM EventType et
            JOIN EventTypeParticipant p ON p.eventTypeId = et.id
            WHERE et.published = true
              AND et.kind = :kind
              AND p.userId = :userId
            """)
    List<EventType> findPublishedCollectiveByParticipantUserId(
            @Param("userId") UUID userId,
            @Param("kind") EventKind kind);

    /**
     * Returns all published COLLECTIVE event types. Used by the periodic readiness scheduler.
     */
    List<EventType> findByPublishedTrueAndKind(EventKind kind);

    default List<EventType> findPublishedCollectiveByParticipantUserId(UUID userId) {
        return findPublishedCollectiveByParticipantUserId(userId, EventKind.COLLECTIVE);
    }

    default List<EventType> findAllPublishedCollective() {
        return findByPublishedTrueAndKind(EventKind.COLLECTIVE);
    }
}
