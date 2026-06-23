package io.bunnycal.form.repository;

import io.bunnycal.form.domain.Form;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormRepository extends JpaRepository<Form, UUID> {

    Optional<Form> findByOwnerIdAndIdAndDeletedAtIsNull(UUID ownerId, UUID id);

    List<Form> findByOwnerIdAndDeletedAtIsNull(UUID ownerId);

    boolean existsByIdAndDeletedAtIsNull(UUID id);
}
