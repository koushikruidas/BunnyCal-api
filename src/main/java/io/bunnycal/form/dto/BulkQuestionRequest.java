package io.bunnycal.form.dto;

import java.util.List;

public record BulkQuestionRequest(
        List<QuestionRequest> questions
) {}
