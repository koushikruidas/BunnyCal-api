package io.bunnycal.auth.onboarding;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.service.CalendarConnectionManagementService;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Version 1 first-run activation contract. The class is intentionally the only definition of
 * onboarding readiness: persisted values remember intent/progress, while completion is derived
 * from the scheduling resources that actually make a booking link work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingService {
    public static final int CONTRACT_VERSION = 1;

    private final UserRepository userRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarConnectionCalendarRepository calendarRepository;
    private final CalendarConnectionManagementService calendarManagementService;
    private final EventTypeRepository eventTypeRepository;
    private final MeterRegistry meterRegistry;

    @Transactional
    public OnboardingStateResponse get(UUID userId) {
        return reconcile(requireUser(userId));
    }

    @Transactional
    public OnboardingStateResponse update(UUID userId, OnboardingUpdateRequest request) {
        User user = requireUser(userId);
        if (request != null) {
            if (request.useCase() != null) user.setOnboardingUseCase(request.useCase());
            if (request.lastStep() != null) user.setOnboardingLastStep(request.lastStep());
            if (Boolean.TRUE.equals(request.availabilityConfirmed())) {
                boolean hasRules = !availabilityRuleRepository
                        .findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId).isEmpty();
                if (!hasRules) {
                    throw new CustomException(ErrorCode.VALIDATION_ERROR,
                            "Save at least one availability window before confirming your hours.");
                }
                user.setAvailabilityConfirmedAt(Instant.now());
            }
        }
        if (user.getOnboardingStatus() == OnboardingStatus.NOT_STARTED) {
            user.setOnboardingStatus(OnboardingStatus.IN_PROGRESS);
        }
        user.setOnboardingVersion(CONTRACT_VERSION);
        userRepository.save(user);
        String step = user.getOnboardingLastStep() == null ? "UNKNOWN" : user.getOnboardingLastStep().name();
        String event = request != null && Boolean.TRUE.equals(request.deferred())
                ? "onboarding.deferred" : "onboarding.step.completed";
        meterRegistry.counter(event, "step", step).increment();
        log.info("onboarding_progress_updated userId={} version={} step={} deferred={} useCase={}",
                userId, CONTRACT_VERSION, step, request != null && Boolean.TRUE.equals(request.deferred()),
                user.getOnboardingUseCase());
        return reconcile(user);
    }

    @Transactional
    public OnboardingStateResponse complete(UUID userId) {
        OnboardingStateResponse state = reconcile(requireUser(userId));
        if (!state.missingRequirements().isEmpty()) {
            throw new CustomException(ErrorCode.ONBOARDING_INCOMPLETE,
                    "Finish setup before completing onboarding: " + String.join(", ", state.missingRequirements()));
        }
        return state;
    }

    /**
     * Turns a just-connected account into a complete scheduling destination. This is deliberately
     * server-side so the dependency chain cannot be applied partially by a browser interruption.
     */
    @Transactional
    public OnboardingStateResponse configureCalendar(UUID userId, UUID connectionId) {
        CalendarConnection connection = connectionRepository.findById(connectionId)
                .filter(candidate -> userId.equals(candidate.getUserId()))
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Calendar connection not found."));
        if (connection.getStatus() != CalendarConnectionStatus.ACTIVE
                && connection.getStatus() != CalendarConnectionStatus.SYNCING) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Wait for the calendar connection to finish syncing before continuing.");
        }
        CalendarConnectionCalendar primary = calendarRepository
                .findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId).stream()
                .filter(calendar -> calendar.isPrimary() && calendar.isCanRead()
                        && calendar.isCanWrite() && !calendar.isHidden())
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.VALIDATION_ERROR,
                        "This account does not have a readable and writable primary calendar."));

        String calendarId = primary.getExternalCalendarId();
        calendarManagementService.setChecksAvailability(userId, connectionId, calendarId, true);
        calendarManagementService.setDefaultWriteback(userId, connectionId);
        calendarManagementService.setWritebackCalendar(userId, connectionId, calendarId);

        ConferencingProviderType conferencing = connection.getProvider() == CalendarProviderType.GOOGLE
                ? ConferencingProviderType.GOOGLE_MEET
                : primary.isSupportsNativeTeams()
                    ? ConferencingProviderType.MICROSOFT_TEAMS
                    : ConferencingProviderType.NONE;
        calendarManagementService.setDefaultConferencing(userId, conferencing);
        meterRegistry.counter("onboarding.calendar.auto_configured",
                "provider", connection.getProvider().name().toLowerCase(),
                "conferencing", conferencing.name().toLowerCase()).increment();
        log.info("onboarding_calendar_auto_configured userId={} connectionId={} calendarId={} provider={} conferencing={}",
                userId, connectionId, calendarId, connection.getProvider(), conferencing);
        return reconcile(requireUser(userId));
    }

    private OnboardingStateResponse reconcile(User user) {
        UUID userId = user.getId();
        boolean availabilityReady = !availabilityRuleRepository
                .findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId).isEmpty();
        boolean availabilityConfirmed = user.getAvailabilityConfirmedAt() != null;
        boolean calendarReady = calendarReady(userId);
        boolean firstEventReady = eventTypeRepository
                .existsByUserIdAndKindAndPublishedTrueAndDeletedAtIsNull(userId, EventKind.ONE_ON_ONE);

        List<String> missing = new ArrayList<>();
        if (!availabilityReady || !availabilityConfirmed) missing.add("availability");
        if (!calendarReady) missing.add("calendar");
        if (!firstEventReady) missing.add("firstEvent");

        if (missing.isEmpty() && user.getOnboardingStatus() != OnboardingStatus.COMPLETED) {
            user.setOnboardingStatus(OnboardingStatus.COMPLETED);
            user.setOnboardingLastStep(OnboardingStep.SUCCESS);
            user.setOnboardingCompletedAt(Instant.now());
            userRepository.save(user);
            meterRegistry.counter("onboarding.completed", "use_case",
                    user.getOnboardingUseCase() == null ? "UNKNOWN" : user.getOnboardingUseCase().name()).increment();
            log.info("onboarding_completed userId={} version={} useCase={}",
                    userId, CONTRACT_VERSION, user.getOnboardingUseCase());
        }

        return new OnboardingStateResponse(
                user.getOnboardingVersion(), user.getOnboardingStatus(), user.getOnboardingUseCase(),
                user.getOnboardingLastStep(), resumeStep(user, availabilityConfirmed && availabilityReady,
                calendarReady, firstEventReady), availabilityConfirmed, availabilityReady,
                calendarReady, firstEventReady, List.copyOf(missing), user.getOnboardingCompletedAt());
    }

    private boolean calendarReady(UUID userId) {
        return connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)
                .filter(connection -> connection.getStatus() == CalendarConnectionStatus.ACTIVE)
                .map(connection -> calendarRepository
                        .findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connection.getId())
                        .stream().anyMatch(this::isReadyCalendar))
                .orElse(false);
    }

    private boolean isReadyCalendar(CalendarConnectionCalendar calendar) {
        return calendar.isSelected()
                && calendar.isChecksAvailability()
                && calendar.isCanRead()
                && calendar.isCanWrite()
                && !calendar.isHidden();
    }

    private OnboardingStep resumeStep(User user, boolean availability, boolean calendar, boolean event) {
        if (user.getOnboardingUseCase() == null) return OnboardingStep.PURPOSE;
        if (!availability) return OnboardingStep.AVAILABILITY;
        if (!calendar) return OnboardingStep.CALENDAR;
        if (!event) return OnboardingStep.FIRST_EVENT;
        return OnboardingStep.SUCCESS;
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED));
    }
}
