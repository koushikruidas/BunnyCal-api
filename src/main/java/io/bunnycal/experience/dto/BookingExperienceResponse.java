package io.bunnycal.experience.dto;

import io.bunnycal.experience.domain.ExperienceStatus;
import java.util.UUID;

public record BookingExperienceResponse(
        UUID id,
        String name,
        String slug,
        UUID eventTypeId,
        UUID formId,
        String primaryColor,
        boolean showBranding,
        ExperienceStatus status,
        long version
) {}
