package io.bunnycal.form.dto;

import java.util.List;
import java.util.UUID;

public record ReorderQuestionsRequest(List<UUID> orderedIds) {}
