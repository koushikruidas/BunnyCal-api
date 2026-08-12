package io.bunnycal.auth.onboarding;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param invitedTeamId   the team whose invitation brought this user in, or null if they arrived on
 *                        their own. Non-null is what makes the first-event step an offer rather
 *                        than a requirement, and is the team to focus once onboarding finishes.
 * @param invitedTeamName that team's name, for framing the steps — "You've joined Customer
 *                        Success". Deliberately only the name: the team may have no event types
 *                        yet, so anything richer risks describing something that does not exist.
 */
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
        Instant completedAt,
        UUID invitedTeamId,
        String invitedTeamName) {
}
