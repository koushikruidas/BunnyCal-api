package io.bunnycal.form.dto;

import java.util.UUID;

public record FormQuestionOptionResponse(UUID id, String label, String value, int sortOrder) {}
