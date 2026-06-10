package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.service.SessionUserResolver;
import io.bunnycal.availability.domain.AvailabilityMode;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.availability.dto.EventTypeSummaryResponse;
import io.bunnycal.availability.dto.PublishReadinessResponse;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import java.time.Duration;
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

    public EventTypeService(EventTypeRepository eventTypeRepository,
                            UserRepository userRepository,
                            SessionUserResolver sessionUserResolver,
                            EventTypeOrchestrationNormalizer orchestrationNormalizer,
                            EventTypeOrchestrationJsonCodec orchestrationJsonCodec,
                            PublishReadinessService publishReadinessService,
                            OutboxPublisher outboxPublisher,
                            TimeSource timeSource) {
        this.eventTypeRepository = eventTypeRepository;
        this.userRepository = userRepository;
        this.sessionUserResolver = sessionUserResolver;
        this.orchestrationNormalizer = orchestrationNormalizer;
        this.orchestrationJsonCodec = orchestrationJsonCodec;
        this.publishReadinessService = publishReadinessService;
        this.outboxPublisher = outboxPublisher;
        this.timeSource = timeSource;
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

        return publishReadinessService.publishReadinessResponse(eventType);
    }

    @Transactional
    public PublishReadinessResponse unpublish(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        eventType.setPublished(false);
        eventTypeRepository.save(eventType);
        return publishReadinessService.publishReadinessResponse(eventType);
    }

    @Transactional(readOnly = true)
    public List<EventTypeSummaryResponse> list(UUID userId) {
        User user = sessionUserResolver.require(userId, "GET:/api/event-types");
        String username = user.getUsername() != null ? user.getUsername() : fallbackUsername(user.getId());

        return eventTypeRepository.findByUserIdOrderByNameAsc(userId).stream()
                .map(et -> toSummary(et, username))
                .toList();
    }

    @Transactional(readOnly = true)
    public EventTypeSummaryResponse get(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        User user = sessionUserResolver.require(actingUserId, "GET:/api/event-types/" + eventTypeId);
        String username = user.getUsername() != null ? user.getUsername() : fallbackUsername(user.getId());
        return toSummary(eventType, username);
    }

    private EventType requireOwnedEventType(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = eventTypeRepository.findById(eventTypeId)
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
        while (eventTypeRepository.existsByUserIdAndSlug(userId, candidate)) {
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
        return new EventTypeSummaryResponse(
                eventType.getId(),
                eventType.getName(),
                eventType.getSlug(),
                "/public/" + username + "/" + eventType.getSlug(),
                eventType.getKind(),
                eventType.getCapacity(),
                eventType.isPublished(),
                degraded,
                availability,
                conference,
                projectionDestination
        );
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
