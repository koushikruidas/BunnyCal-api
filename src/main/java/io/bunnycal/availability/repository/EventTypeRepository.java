package io.bunnycal.availability.repository;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventTypeRepository extends JpaRepository<EventType, UUID> {

    Optional<EventType> findByIdAndUserId(UUID id, UUID userId);
    Optional<EventType> findByUserIdAndSlug(UUID userId, String slug);
    List<EventType> findByUserIdOrderByNameAsc(UUID userId);
    boolean existsByUserIdAndSlug(UUID userId, String slug);

    // Active-only lookups: deleted_at IS NULL. Used by all user-facing/public read paths.
    // Booking, calendar-sync, and audit pipelines deliberately keep the unfiltered methods
    // above so existing bookings can still resolve a soft-deleted event type.
    Optional<EventType> findByIdAndDeletedAtIsNull(UUID id);
    Optional<EventType> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);
    Optional<EventType> findByUserIdAndSlugAndDeletedAtIsNull(UUID userId, String slug);
    List<EventType> findByUserIdAndDeletedAtIsNullOrderByNameAsc(UUID userId);
    boolean existsByUserIdAndSlugAndDeletedAtIsNull(UUID userId, String slug);
    boolean existsByUserIdAndKindAndPublishedTrueAndDeletedAtIsNull(UUID userId, EventKind kind);

    /**
     * Returns all published, non-deleted COLLECTIVE event types that have {@code userId} as a
     * participant. Used by the readiness enforcer to find affected events when a participant's
     * state changes.
     */
    @Query("""
            SELECT et
            FROM EventType et
            JOIN EventTypeParticipant p ON p.eventTypeId = et.id
            WHERE et.published = true
              AND et.deletedAt IS NULL
              AND et.kind = :kind
              AND p.userId = :userId
            """)
    List<EventType> findPublishedActiveCollectiveByParticipantUserId(
            @Param("userId") UUID userId,
            @Param("kind") EventKind kind);

    /**
     * Returns all published, non-deleted COLLECTIVE event types. Used by the periodic readiness
     * scheduler.
     */
    @Query("SELECT et FROM EventType et WHERE et.published = true AND et.deletedAt IS NULL AND et.kind = :kind")
    List<EventType> findPublishedActiveByKind(@Param("kind") EventKind kind);

    default List<EventType> findPublishedCollectiveByParticipantUserId(UUID userId) {
        return findPublishedActiveCollectiveByParticipantUserId(userId, EventKind.COLLECTIVE);
    }

    default List<EventType> findAllPublishedCollective() {
        return findPublishedActiveByKind(EventKind.COLLECTIVE);
    }

    @Modifying
    @Query("update EventType et set et.deletedAt = :deletedAt, et.published = false where et.userId = :userId and et.deletedAt is null")
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("deletedAt") Instant deletedAt);
}
