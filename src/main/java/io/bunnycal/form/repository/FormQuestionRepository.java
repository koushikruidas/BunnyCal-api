package io.bunnycal.form.repository;

import io.bunnycal.form.domain.FormQuestion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormQuestionRepository extends JpaRepository<FormQuestion, UUID> {

    List<FormQuestion> findByFormIdOrderBySortOrder(UUID formId);

    Optional<FormQuestion> findByIdAndFormId(UUID id, UUID formId);

    void deleteByFormId(UUID formId);
}
