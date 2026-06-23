package io.bunnycal.form.dto;

import io.bunnycal.form.domain.QuestionType;
import java.util.List;
import java.util.UUID;

public record QuestionResponse(
        UUID id,
        String questionText,
        QuestionType questionType,
        boolean required,
        int sortOrder,
        List<FormQuestionOptionResponse> options
) {}
