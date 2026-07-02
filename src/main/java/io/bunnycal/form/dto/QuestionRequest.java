package io.bunnycal.form.dto;

import io.bunnycal.form.domain.QuestionType;
import java.util.List;

public record QuestionRequest(
        String questionText,
        QuestionType questionType,
        boolean required,
        List<FormQuestionOptionRequest> options
) {}
