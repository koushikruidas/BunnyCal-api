package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.service.SessionUserResolver;
import io.bunnycal.availability.domain.AvailabilityMode;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.availability.dto.EventTypeSummaryResponse;
import io.bunnycal.availability.dto.PublishReadinessResponse;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.billing.entitlement.EntitlementService;
import io.bunnycal.billing.entitlement.Feature;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.logging.OpsLoggers;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.common.time.TimeSource;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventTypeService {
    private final EventTypeRepository eventTypeRepository;
    private final UserRepository userRepository;
    private final SessionUserResolver sessionUserResolver;
    private final EventTypeOrchestrationNormalizer orchestrationNormalizer;
    private final EventTypeOrchestrationJsonCodec orchestrationJsonCodec;
    private final PublishReadinessService publishReadinessService;
    private final OutboxPublisher outboxPublisher;
    private final TimeSource timeSource;
    private final BookingExperienceRepository experienceRepository;
    private final EntitlementService entitlementService;
    private final GroupEventReservationWindowRepository reservationWindowRepository;

    public EventTypeService(EventTypeRepository eventTypeRepository,
                            UserRepository userRepository,
                            SessionUserResolver sessionUserResolver,
                            EventTypeOrchestrationNormalizer orchestrationNormalizer,
                            EventTypeOrchestrationJsonCodec orchestrationJsonCodec,
                            PublishReadinessService publishReadinessService,
                            OutboxPublisher outboxPublisher,
                            TimeSource timeSource,
                            BookingExperienceRepository experienceRepository,
                            EntitlementService entitlementService,
                            GroupEventReservationWindowRepository reservationWindowRepository) {
        this.eventTypeRepository = eventTypeRepository;
        this.userRepository = userRepository;
        this.sessionUserResolver = sessionUserResolver;
        this.orchestrationNormalizer = orchestrationNormalizer;
        this.orchestrationJsonCodec = orchestrationJsonCodec;
        this.publishReadinessService = publishReadinessService;
        this.outboxPublisher = outboxPublisher;
        this.timeSource = timeSource;
        this.experienceRepository = experienceRepository;
        this.entitlementService = entitlementService;
        this.reservationWindowRepository = reservationWindowRepository;
    }


    @Transactional
    public EventTypeSummaryResponse create(UUID userId, CreateEventTypeRequest request) {
        validate(request);
        User user = sessionUserResolver.require(userId, "POST:/api/event-types");

        String username = ensureUsername(user);
        String slug = uniqueSlug(userId, requestedOrDerivedSlug(request));
        EventTypeOrchestrationNormalizer.NormalizedOrchestration orchestration = orchestrationNormalizer.normalize(userId, request);

        AvailabilityMode availabilityMode = (request.availabilityCalendars() != null && !request.availabilityCalendars().isEmpty())
                ? AvailabilityMode.SELECTED
                : AvailabilityMode.ALL_CONNECTED;

        EventKind kind = request.kind() != null ? request.kind() : EventKind.ONE_ON_ONE;
        // Premium event kinds (Group/Round Robin/Collective) require the matching plan feature;
        // One-to-One is always allowed (Spec Ch2 §5). One-time gate at creation — kind is
        // immutable thereafter. All authorization flows through EntitlementService.
        Feature requiredFeature = EventKindEntitlements.requiredFeature(kind);
        if (requiredFeature != null) {
            entitlementService.require(userId, requiredFeature);
        }
        int capacity = request.capacity() != null ? request.capacity() : 1;
        // COLLECTIVE events start unpublished — no participants or readiness evaluation has
        // occurred yet. All other kinds start published (single-host, always ready).
        boolean startPublished = kind != EventKind.COLLECTIVE;

        EventTypeOrchestrationNormalizer.ProjectionDestination proj = orchestration.projectionDestination();
        EventType eventType = EventType.builder()
                .userId(userId)
                .name(request.name().trim())
                .description(trimToNull(request.description()))
                .location(trimToNull(request.location()))
                .organizerCalendarConnectionId(proj != null ? proj.connectionId() : null)
                .projectionConnectionId(proj != null ? proj.connectionId() : null)
                .projectionCalendarId(proj != null ? proj.calendarId() : null)
                .projectionProvider(proj != null ? io.bunnycal.calendar.domain.CalendarProviderType.valueOf(
                        proj.provider().toUpperCase(Locale.ROOT)) : null)
                .availabilityCalendarsJson(orchestrationJsonCodec.serializeAvailabilityBindings(orchestration.availabilityBindings()))
                .availabilityMode(availabilityMode)
                .conferencingProvider(orchestration.conferencing().provider())
                .customConferenceUrl(orchestration.conferencing().customUrl())
                .slug(slug)
                .duration(Duration.ofMinutes(request.durationMinutes()))
                .bufferBefore(Duration.ofMinutes(request.bufferBeforeMinutes()))
                .bufferAfter(Duration.ofMinutes(request.bufferAfterMinutes()))
                .slotInterval(Duration.ofMinutes(request.slotIntervalMinutes()))
                .minNotice(Duration.ofMinutes(request.minNoticeMinutes()))
                .maxAdvance(Duration.ofDays(request.maxAdvanceDays()))
                .holdDuration(Duration.ofMinutes(request.holdDurationMinutes()))
                .kind(kind)
                .capacity(capacity)
                .published(startPublished)
                .build();

        EventType saved = eventTypeRepository.save(eventType);
        OpsLoggers.HOST.info(
                "event_type_created hostId={} eventTypeId={} eventTypeKind={} eventTypeName=\"{}\" slug={} durationMin={} published={}",
                userId, saved.getId(), saved.getKind(), saved.getName(), saved.getSlug(),
                saved.getDuration().toMinutes(), saved.isPublished());
        return toSummary(saved, username);
    }

    @Transactional
    public PublishReadinessResponse publish(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        PublishReadinessService.CollectiveReadinessSummary summary =
                publishReadinessService.evaluate(eventType);

        if (!summary.publishable()) {
            throw new CustomException(ErrorCode.UNPUBLISHABLE_EVENT_TYPE,
                    "Cannot publish: " + String.join("; ", summary.reasons()));
        }

        eventType.setPublished(true);
        eventTypeRepository.save(eventType);

        EventTypeLifecycleOutboxPayload payload = new EventTypeLifecycleOutboxPayload(
                eventType.getId(), eventType.getName(), eventType.getUserId(),
                "Owner published.",
                List.of(), timeSource.now());
        outboxPublisher.publish(
                EventTypeLifecycleOutboxPayload.AGGREGATE_TYPE,
                eventType.getId(),
                new OutboxPayloadEnvelope(
                        java.util.UUID.randomUUID().toString(),
                        EventTypeLifecycleOutboxPayload.EVENT_PUBLISHED, 1, payload));
        OpsLoggers.HOST.info(
                "event_type_published hostId={} eventTypeId={} eventTypeKind={} eventTypeName=\"{}\" published={}",
                actingUserId, eventType.getId(), eventType.getKind(), eventType.getName(), true);

        return publishReadinessService.publishReadinessResponse(eventType);
    }

    @Transactional
    public PublishReadinessResponse unpublish(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        eventType.setPublished(false);
        eventTypeRepository.save(eventType);
        OpsLoggers.HOST.info(
                "event_type_unpublished hostId={} eventTypeId={} eventTypeKind={} eventTypeName=\"{}\" published={}",
                actingUserId, eventType.getId(), eventType.getKind(), eventType.getName(), false);
        return publishReadinessService.publishReadinessResponse(eventType);
    }

    @Transactional(readOnly = true)
    public List<EventTypeSummaryResponse> list(UUID userId) {
        User user = sessionUserResolver.require(userId, "GET:/api/event-types");
        String username = user.getUsername() != null ? user.getUsername() : fallbackUsername(user.getId());

        return eventTypeRepository.findByUserIdAndDeletedAtIsNullOrderByNameAsc(userId).stream()
                .map(et -> toSummary(et, username))
                .toList();
    }

    /**
     * Soft-deletes an owned event type: sets deleted_at so it disappears from all active
     * workflows and stops accepting new bookings. Existing bookings and history are untouched.
     */
    @Transactional
    public void delete(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        // Block deletion while any non-deleted booking experience still references this event type.
        // DRAFT and ARCHIVED experiences are included because either can be activated later,
        // and ACTIVE experiences would break immediately on the public embed path.
        if (experienceRepository.existsByEventTypeIdAndDeletedAtIsNull(eventTypeId)) {
            throw new CustomException(ErrorCode.EVENT_TYPE_ATTACHED_TO_EXPERIENCE);
        }
        eventType.setDeletedAt(timeSource.now());
        eventTypeRepository.save(eventType);
        OpsLoggers.HOST.info(
                "event_type_archived hostId={} eventTypeId={} eventTypeKind={} eventTypeName=\"{}\" archived={}",
                actingUserId, eventType.getId(), eventType.getKind(), eventType.getName(), true);
    }

    @Transactional(readOnly = true)
    public EventTypeSummaryResponse get(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        User user = sessionUserResolver.require(actingUserId, "GET:/api/event-types/" + eventTypeId);
        String username = user.getUsername() != null ? user.getUsername() : fallbackUsername(user.getId());
        return toSummary(eventType, username);
    }

    private EventType requireOwnedEventType(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = eventTypeRepository.findByIdAndDeletedAtIsNull(eventTypeId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        if (!eventType.getUserId().equals(actingUserId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "You do not own this event type.");
        }
        return eventType;
    }

    private static void validate(CreateEventTypeRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "name is required.");
        }
        if (request.durationMinutes() == null || request.durationMinutes() <= 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "durationMinutes must be > 0.");
        }
        if (request.slotIntervalMinutes() == null || request.slotIntervalMinutes() <= 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "slotIntervalMinutes must be > 0.");
        }
        if (request.bufferBeforeMinutes() == null || request.bufferBeforeMinutes() < 0
                || request.bufferAfterMinutes() == null || request.bufferAfterMinutes() < 0
                || request.minNoticeMinutes() == null || request.minNoticeMinutes() < 0
                || request.maxAdvanceDays() == null || request.maxAdvanceDays() < 0
                || request.holdDurationMinutes() == null || request.holdDurationMinutes() <= 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "buffer/notice/advance/hold values are invalid.");
        }
        EventKind kind = request.kind() != null ? request.kind() : EventKind.ONE_ON_ONE;
        int capacity = request.capacity() != null ? request.capacity() : 1;
        if (kind == EventKind.ONE_ON_ONE && capacity != 1) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "ONE_ON_ONE event types must have capacity=1.");
        }
        if (kind == EventKind.GROUP && capacity < 2) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "GROUP event types must have capacity >= 2.");
        }
    }

    private String ensureUsername(User user) {
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        String username = fallbackUsername(user.getId());
        user.setUsername(username);
        userRepository.save(user);
        return username;
    }

    private String uniqueSlug(UUID userId, String baseSlug) {
        String candidate = baseSlug;
        int suffix = 1;
        while (eventTypeRepository.existsByUserIdAndSlugAndDeletedAtIsNull(userId, candidate)) {
            suffix++;
            candidate = baseSlug + "-" + suffix;
        }
        return candidate;
    }

    private static String requestedOrDerivedSlug(CreateEventTypeRequest request) {
        if (request.slug() != null && !request.slug().isBlank()) {
            return normalizeSlug(request.slug());
        }
        return normalizeSlug(request.name());
    }

    private static String normalizeSlug(String input) {
        String s = input.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+", "").replaceAll("-+$", "");
        return s.isBlank() ? "event" : s;
    }

    private static String fallbackUsername(UUID id) {
        return "user-" + id.toString().substring(0, 8);
    }

    private EventTypeSummaryResponse toSummary(EventType eventType, String username) {
        List<EventTypeSummaryResponse.AvailabilityCalendarResponse> availability =
                orchestrationJsonCodec.toAvailabilityResponse(eventType.getAvailabilityCalendarsJson());
        EventTypeSummaryResponse.ConferenceResponse conference = new EventTypeSummaryResponse.ConferenceResponse(
                eventType.getConferencingProvider() != ConferencingProviderType.NONE,
                eventType.getConferencingProvider().name(),
                eventType.getCustomConferenceUrl()
        );
        EventTypeSummaryResponse.ProjectionDestinationResponse projectionDestination =
                new EventTypeSummaryResponse.ProjectionDestinationResponse(
                        eventType.getProjectionProvider() == null ? null : eventType.getProjectionProvider().name(),
                        eventType.getProjectionConnectionId(),
                        eventType.getProjectionCalendarId());
        // Evaluate degraded only for COLLECTIVE; other kinds are single-host and always non-degraded.
        boolean degraded = eventType.getKind() == EventKind.COLLECTIVE
                && publishReadinessService.evaluate(eventType).degraded();
        GroupSeriesBounds groupSeriesBounds = eventType.getKind() == EventKind.GROUP
                ? summarizeGroupSeriesBounds(eventType.getId())
                : GroupSeriesBounds.empty();
        return new EventTypeSummaryResponse(
                eventType.getId(),
                eventType.getName(),
                eventType.getSlug(),
                "/public/" + username + "/" + eventType.getSlug(),
                eventType.getKind(),
                eventType.getCapacity(),
                (int) eventType.getDuration().toMinutes(),
                eventType.isPublished(),
                degraded,
                groupSeriesBounds.startDate(),
                groupSeriesBounds.endDate(),
                availability,
                conference,
                projectionDestination
        );
    }

    private GroupSeriesBounds summarizeGroupSeriesBounds(UUID eventTypeId) {
        List<GroupEventReservationWindow> windows = reservationWindowRepository.findByEventTypeId(eventTypeId);
        if (windows.isEmpty()) return GroupSeriesBounds.empty();

        LocalDate minStart = null;
        LocalDate maxEnd = null;
        boolean openEnded = false;

        for (GroupEventReservationWindow window : windows) {
            LocalDate start = reservationWindowStart(window);
            if (start != null && (minStart == null || start.isBefore(minStart))) {
                minStart = start;
            }

            LocalDate end = reservationWindowEnd(window);
            if (end == null && isOpenEnded(window)) {
                openEnded = true;
            } else if (end != null && (maxEnd == null || end.isAfter(maxEnd))) {
                maxEnd = end;
            }
        }

        return new GroupSeriesBounds(minStart, openEnded ? null : maxEnd);
    }

    private static LocalDate reservationWindowStart(GroupEventReservationWindow window) {
        return window.getScheduleType() == ScheduleType.ONE_TIME ? window.getEventDate() : window.getStartDate();
    }

    private static LocalDate reservationWindowEnd(GroupEventReservationWindow window) {
        if (window.getScheduleType() == ScheduleType.ONE_TIME) {
            return window.getEventDate();
        }
        if (window.getRecurrenceEndMode() == RecurrenceEndMode.UNTIL_DATE) {
            return window.getUntilDate();
        }
        if (window.getRecurrenceEndMode() == RecurrenceEndMode.OCCURRENCE_COUNT
                && window.getStartDate() != null
                && window.getOccurrenceCount() != null
                && window.getOccurrenceCount() > 0) {
            return window.getStartDate().plusWeeks(window.getOccurrenceCount() - 1L);
        }
        return null;
    }

    private static boolean isOpenEnded(GroupEventReservationWindow window) {
        return window.getScheduleType() == ScheduleType.RECURRING
                && (window.getRecurrenceEndMode() == null || window.getRecurrenceEndMode() == RecurrenceEndMode.NONE);
    }

    private record GroupSeriesBounds(LocalDate startDate, LocalDate endDate) {
        private static GroupSeriesBounds empty() {
            return new GroupSeriesBounds(null, null);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
