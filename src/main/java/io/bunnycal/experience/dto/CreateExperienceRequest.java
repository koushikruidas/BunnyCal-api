package io.bunnycal.experience.dto;

import java.util.UUID;

public record CreateExperienceRequest(
        String name,
        UUID eventTypeId,
        UUID formId,
        String primaryColor,
        boolean showBranding
) {}
