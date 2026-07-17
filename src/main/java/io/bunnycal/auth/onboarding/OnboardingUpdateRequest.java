package io.bunnycal.auth.onboarding;

public record OnboardingUpdateRequest(
        OnboardingUseCase useCase,
        OnboardingStep lastStep,
        Boolean availabilityConfirmed,
        Boolean deferred) {
}
