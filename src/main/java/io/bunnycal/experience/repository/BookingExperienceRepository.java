package io.bunnycal.experience.repository;

import io.bunnycal.experience.domain.BookingExperience;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingExperienceRepository extends JpaRepository<BookingExperience, UUID> {

    Optional<BookingExperience> findBySlugAndDeletedAtIsNull(String slug);

    List<BookingExperience> findByOwnerIdAndDeletedAtIsNull(UUID ownerId);

    Optional<BookingExperience> findByOwnerIdAndIdAndDeletedAtIsNull(UUID ownerId, UUID id);

    boolean existsByEventTypeIdAndDeletedAtIsNull(UUID eventTypeId);

    boolean existsByFormIdAndStatusNotAndDeletedAtIsNull(UUID formId, io.bunnycal.experience.domain.ExperienceStatus status);

    boolean existsByFormIdAndDeletedAtIsNull(UUID formId);

    @Modifying
    @Query("update BookingExperience be set be.deletedAt = :deletedAt where be.ownerId = :ownerId and be.deletedAt is null")
    void softDeleteByOwnerId(@Param("ownerId") UUID ownerId, @Param("deletedAt") Instant deletedAt);
}
