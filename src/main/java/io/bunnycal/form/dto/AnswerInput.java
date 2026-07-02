package io.bunnycal.form.dto;

import java.util.List;
import java.util.UUID;

public record AnswerInput(
        UUID questionId,
        String answerText,
        List<String> answerOptions
) {}
