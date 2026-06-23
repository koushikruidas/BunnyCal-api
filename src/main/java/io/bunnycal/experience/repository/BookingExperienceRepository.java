package io.bunnycal.experience.repository;

import io.bunnycal.experience.domain.BookingExperience;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingExperienceRepository extends JpaRepository<BookingExperience, UUID> {

    Optional<BookingExperience> findBySlugAndDeletedAtIsNull(String slug);

    List<BookingExperience> findByOwnerIdAndDeletedAtIsNull(UUID ownerId);

    Optional<BookingExperience> findByOwnerIdAndIdAndDeletedAtIsNull(UUID ownerId, UUID id);

    boolean existsByFormIdAndStatusNotAndDeletedAtIsNull(UUID formId, io.bunnycal.experience.domain.ExperienceStatus status);
}
