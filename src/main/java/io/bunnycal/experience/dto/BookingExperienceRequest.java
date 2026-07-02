package io.bunnycal.experience.dto;

import java.util.UUID;

public record BookingExperienceRequest(
        String name,
        UUID formId,
        String primaryColor,
        boolean showBranding
) {}
