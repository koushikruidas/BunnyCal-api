package io.bunnycal.form.dto;

import java.util.List;
import java.util.UUID;

public record AnswerSnapshot(
        UUID questionId,
        String questionLabelSnapshot,
        String questionTypeSnapshot,
        String answerValue,
        List<String> answerOptions
) {}
