package io.bunnycal.form.repository;

import io.bunnycal.form.domain.Form;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FormRepository extends JpaRepository<Form, UUID> {

    Optional<Form> findByOwnerIdAndIdAndDeletedAtIsNull(UUID ownerId, UUID id);

    List<Form> findByOwnerIdAndDeletedAtIsNull(UUID ownerId);

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    @Modifying
    @Query("update Form f set f.deletedAt = :deletedAt where f.ownerId = :ownerId and f.deletedAt is null")
    void softDeleteByOwnerId(@Param("ownerId") UUID ownerId, @Param("deletedAt") Instant deletedAt);
}
