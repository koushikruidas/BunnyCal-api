package io.bunnycal.admin.users.dto;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.onboarding.OnboardingStatus;
import io.bunnycal.auth.onboarding.OnboardingStep;
import io.bunnycal.auth.onboarding.OnboardingUseCase;
import java.time.Instant;

/**
 * "What has this user actually done" — the onboarding funnel position plus counts of the
 * things a real user creates. Answers the support question that previously required a
 * hand-written SQL session: did they finish setup, and did they build anything?
 *
 * <p>The counts are deliberately cheap aggregates rather than embedded lists; the full
 * lists live in their own tabs ({@link AdminUserEventTypeDto},
 * {@link AdminUserCalendarConnectionDto}).
 *
 * <p>{@code lastActivityAt} is the user row's {@code updated_at}. That tracks profile and
 * onboarding writes, not sign-ins — a user who logs in and only reads leaves it untouched
 * — so treat it as a floor on activity, never as a last-seen timestamp.
 */
public record AdminUserActivityDto(
        OnboardingStatus onboardingStatus,
        OnboardingStep onboardingLastStep,
        OnboardingUseCase onboardingUseCase,
        Instant availabilityConfirmedAt,
        Instant onboardingCompletedAt,
        Instant signedUpAt,
        Instant lastActivityAt,
        String timezone,
        boolean timezoneAuto,
        long eventTypeCount,
        long publishedEventTypeCount,
        long calendarConnectionCount,
        long availabilityRuleCount,
        long bookingsAsHostCount) {

    public static AdminUserActivityDto of(
            User u,
            long eventTypeCount,
            long publishedEventTypeCount,
            long calendarConnectionCount,
            long availabilityRuleCount,
            long bookingsAsHostCount) {
        return new AdminUserActivityDto(
                u.getOnboardingStatus(),
                u.getOnboardingLastStep(),
                u.getOnboardingUseCase(),
                u.getAvailabilityConfirmedAt(),
                u.getOnboardingCompletedAt(),
                u.getCreatedAt(),
                u.getUpdatedAt(),
                u.getTimezone(),
                u.isTimezoneAuto(),
                eventTypeCount,
                publishedEventTypeCount,
                calendarConnectionCount,
                availabilityRuleCount,
                bookingsAsHostCount);
    }
}
