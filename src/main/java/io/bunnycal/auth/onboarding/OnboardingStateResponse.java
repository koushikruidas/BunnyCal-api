package io.bunnycal.auth.onboarding;

import java.time.Instant;
import java.util.List;

public record OnboardingStateResponse(
        int version,
        OnboardingStatus status,
        OnboardingUseCase useCase,
        OnboardingStep lastStep,
        OnboardingStep resumeStep,
        boolean availabilityConfirmed,
        boolean availabilityReady,
        boolean calendarReady,
        boolean firstEventReady,
        List<String> missingRequirements,
        Instant completedAt) {
}
