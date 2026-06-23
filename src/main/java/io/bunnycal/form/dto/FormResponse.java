package io.bunnycal.form.dto;

import java.util.List;
import java.util.UUID;

public record FormResponse(
        UUID id,
        String name,
        String description,
        long version,
        List<QuestionResponse> questions
) {}
