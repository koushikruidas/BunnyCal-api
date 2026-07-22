package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.service.SessionUserResolver;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.GroupHostNotificationMode;
import io.bunnycal.availability.domain.EventAvailabilityMode;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.availability.dto.UpdateEventTypeRequest;
import io.bunnycal.availability.dto.EventTypeSummaryResponse;
import io.bunnycal.availability.dto.PublishReadinessResponse;
import io.bunnycal.availability.repository.EventAvailabilityWindowRepository;
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
import io.bunnycal.hostpayments.service.EventPaymentConfigService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventTypeService {

    public static final int MAX_GROUP_CAPACITY = 9_999;
    private final EventTypeRepository eventTypeRepository;
    private final UserRepository userRepository;
    private final SessionUserResolver sessionUserResolver;
    private final EventTypeOrchestrationNormalizer orchestrationNormalizer;
    private final PublishReadinessService publishReadinessService;
    private final OutboxPublisher outboxPublisher;
    private final TimeSource timeSource;
    private final BookingExperienceRepository experienceRepository;
    private final EntitlementService entitlementService;
    private final GroupEventReservationWindowRepository reservationWindowRepository;
    private final EventAvailabilityWindowRepository eventAvailabilityWindowRepository;
    private final SlotCacheVersionService slotCacheVersionService;
    private final EventPaymentConfigService eventPaymentConfigService;

    @Autowired
    public EventTypeService(EventTypeRepository eventTypeRepository,
                            UserRepository userRepository,
                            SessionUserResolver sessionUserResolver,
                            EventTypeOrchestrationNormalizer orchestrationNormalizer,
                            PublishReadinessService publishReadinessService,
                            OutboxPublisher outboxPublisher,
                            TimeSource timeSource,
                            BookingExperienceRepository experienceRepository,
                            EntitlementService entitlementService,
                            GroupEventReservationWindowRepository reservationWindowRepository,
                            EventAvailabilityWindowRepository eventAvailabilityWindowRepository,
                            SlotCacheVersionService slotCacheVersionService,
                            @Nullable EventPaymentConfigService eventPaymentConfigService) {
        this.eventTypeRepository = eventTypeRepository;
        this.userRepository = userRepository;
        this.sessionUserResolver = sessionUserResolver;
        this.orchestrationNormalizer = orchestrationNormalizer;
        this.publishReadinessService = publishReadinessService;
        this.outboxPublisher = outboxPublisher;
        this.timeSource = timeSource;
        this.experienceRepository = experienceRepository;
        this.entitlementService = entitlementService;
        this.reservationWindowRepository = reservationWindowRepository;
        this.eventAvailabilityWindowRepository = eventAvailabilityWindowRepository;
        this.slotCacheVersionService = slotCacheVersionService;
        this.eventPaymentConfigService = eventPaymentConfigService;
    }

    /** Back-compatible constructor for focused unit tests that do not exercise payments. */
    public EventTypeService(EventTypeRepository eventTypeRepository,
                            UserRepository userRepository,
                            SessionUserResolver sessionUserResolver,
                            EventTypeOrchestrationNormalizer orchestrationNormalizer,
                            PublishReadinessService publishReadinessService,
                            OutboxPublisher outboxPublisher,
                            TimeSource timeSource,
                            BookingExperienceRepository experienceRepository,
                            EntitlementService entitlementService,
                            GroupEventReservationWindowRepository reservationWindowRepository,
                            EventAvailabilityWindowRepository eventAvailabilityWindowRepository) {
        this(eventTypeRepository, userRepository, sessionUserResolver, orchestrationNormalizer,
                publishReadinessService, outboxPublisher, timeSource, experienceRepository,
                entitlementService, reservationWindowRepository, eventAvailabilityWindowRepository, null, null);
    }


    @Transactional
    public EventTypeSummaryResponse create(UUID userId, CreateEventTypeRequest request) {
        validate(request);
        User user = sessionUserResolver.require(userId, "POST:/api/event-types");

        String username = ensureUsername(user);
        String slug = uniqueSlug(userId, requestedOrDerivedSlug(request));
        EventTypeOrchestrationNormalizer.ConferencingConfig conferencing =
                orchestrationNormalizer.normalize(request);

        EventKind kind = request.kind() != null ? request.kind() : EventKind.ONE_ON_ONE;
        // Premium event kinds (Group/Round Robin/Collective) require the matching plan feature;
        // One-to-One is always allowed (Spec Ch2 §5). One-time gate at creation — kind is
        // immutable thereafter. All authorization flows through EntitlementService.
        Feature requiredFeature = EventKindEntitlements.requiredFeature(kind);
        if (requiredFeature != null) {
            entitlementService.require(userId, requiredFeature);
        }
        int capacity = request.capacity() != null ? request.capacity() : 1;
        GroupHostNotificationMode groupHostNotificationMode = kind == EventKind.GROUP
                ? request.groupHostNotificationMode() == null
                    ? GroupHostNotificationMode.SMART_SUMMARY
                    : request.groupHostNotificationMode()
                : GroupHostNotificationMode.SMART_SUMMARY;
        // COLLECTIVE events start unpublished — no participants or readiness evaluation has
        // occurred yet. All other kinds start published (single-host, always ready).
        boolean startPublished = kind != EventKind.COLLECTIVE;

        EventType eventType = EventType.builder()
                .userId(userId)
                .name(request.name().trim())
                .description(trimToNull(request.description()))
                .location(trimToNull(request.location()))
                .conferencingProvider(conferencing.providerType())
                .customConferenceUrl(conferencing.customUrl())
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
                .groupHostNotificationMode(groupHostNotificationMode)
                .published(startPublished)
                .build();

        EventType saved = eventTypeRepository.save(eventType);
        if (eventPaymentConfigService != null) eventPaymentConfigService.apply(userId, saved.getId(), request.payment());
        OpsLoggers.HOST.info(
                "event_type_created hostId={} eventTypeId={} eventTypeKind={} eventTypeName=\"{}\" slug={} durationMin={} published={}",
                userId, saved.getId(), saved.getKind(), saved.getName(), saved.getSlug(),
                saved.getDuration().toMinutes(), saved.isPublished());
        return toSummary(saved, username);
    }

    /**
     * Partial update. An absent field leaves the stored value alone.
     *
     * <p>No calendar fields: which calendars block you, and which one receives your bookings, are
     * settings on your account rather than on each event type — change them once and every event
     * type follows.
     *
     * <p>{@code kind} is immutable: it gates entitlements at creation, and every downstream
     * assumption about who receives the calendar write is derived from it.
     */
    @Transactional
    public EventTypeSummaryResponse update(UUID actingUserId, UUID eventTypeId, UpdateEventTypeRequest request) {
        if (request == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "request body is required.");
        }
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        User user = sessionUserResolver.require(actingUserId, "PUT:/api/event-types");
        validateUpdate(eventType, request);

        EventTypeOrchestrationNormalizer.ConferencingConfig conferencing =
                orchestrationNormalizer.normalizeForDraftMutation(eventType, request.conference());

        if (request.name() != null) eventType.setName(request.name().trim());
        if (request.description() != null) eventType.setDescription(trimToNull(request.description()));
        if (request.location() != null) eventType.setLocation(trimToNull(request.location()));
        if (request.durationMinutes() != null) eventType.setDuration(Duration.ofMinutes(request.durationMinutes()));
        if (request.bufferBeforeMinutes() != null) eventType.setBufferBefore(Duration.ofMinutes(request.bufferBeforeMinutes()));
        if (request.bufferAfterMinutes() != null) eventType.setBufferAfter(Duration.ofMinutes(request.bufferAfterMinutes()));
        if (request.slotIntervalMinutes() != null) eventType.setSlotInterval(Duration.ofMinutes(request.slotIntervalMinutes()));
        if (request.minNoticeMinutes() != null) eventType.setMinNotice(Duration.ofMinutes(request.minNoticeMinutes()));
        if (request.maxAdvanceDays() != null) eventType.setMaxAdvance(Duration.ofDays(request.maxAdvanceDays()));
        if (request.holdDurationMinutes() != null) eventType.setHoldDuration(Duration.ofMinutes(request.holdDurationMinutes()));
        if (request.capacity() != null) eventType.setCapacity(request.capacity());
        if (request.groupHostNotificationMode() != null) {
            eventType.setGroupHostNotificationMode(request.groupHostNotificationMode());
        }

        if (request.conference() != null) {
            eventType.setConferencingProvider(conferencing.providerType());
            eventType.setCustomConferenceUrl(conferencing.customUrl());
        }

        EventType saved = eventTypeRepository.save(eventType);
        if (eventPaymentConfigService != null) eventPaymentConfigService.apply(actingUserId, saved.getId(), request.payment());
        if (slotCacheVersionService != null && changesSlotProjection(request)) {
            slotCacheVersionService.bumpVersionAfterCommit(saved.getUserId());
        }
        OpsLoggers.HOST.info(
                "event_type_updated hostId={} eventTypeId={} eventTypeKind={} conferencingProvider={}",
                actingUserId, saved.getId(), saved.getKind(), saved.getConferencingProvider());
        return toSummary(saved, ensureUsername(user));
    }

    /**
     * These fields are consumed directly by slot generation. Cached availability must move to a
     * new version after the transaction commits or public pages keep serving the old slot length
     * and grid until the cache TTL expires.
     */
    private static boolean changesSlotProjection(UpdateEventTypeRequest request) {
        return request.durationMinutes() != null
                || request.bufferBeforeMinutes() != null
                || request.bufferAfterMinutes() != null
                || request.slotIntervalMinutes() != null
                || request.minNoticeMinutes() != null
                || request.maxAdvanceDays() != null;
    }

    private static void validateUpdate(EventType eventType, UpdateEventTypeRequest request) {
        if (request.name() != null && request.name().isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "name must not be blank.");
        }
        if (request.durationMinutes() != null && request.durationMinutes() <= 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "durationMinutes must be > 0.");
        }
        if (request.slotIntervalMinutes() != null && request.slotIntervalMinutes() <= 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "slotIntervalMinutes must be > 0.");
        }
        if (request.holdDurationMinutes() != null && request.holdDurationMinutes() <= 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "holdDurationMinutes must be > 0.");
        }
        if (isNegative(request.bufferBeforeMinutes()) || isNegative(request.bufferAfterMinutes())
                || isNegative(request.minNoticeMinutes()) || isNegative(request.maxAdvanceDays())) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "buffer/notice/advance values must not be negative.");
        }
        if (request.capacity() != null) {
            if (eventType.getKind() != EventKind.GROUP) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "capacity can only be changed for GROUP event types.");
            }
            validateGroupCapacity(request.capacity());
        }
        if (request.groupHostNotificationMode() != null && eventType.getKind() != EventKind.GROUP) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "groupHostNotificationMode is only supported for GROUP event types.");
        }
    }

    private static boolean isNegative(Integer value) {
        return value != null && value < 0;
    }

    @Transactional
    public PublishReadinessResponse publish(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        if (eventPaymentConfigService != null) {
            eventPaymentConfigService.requireBookable(eventTypeId, actingUserId);
        }
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
        if (kind == EventKind.GROUP) {
            validateGroupCapacity(capacity);
        }
    }

    private static void validateGroupCapacity(int capacity) {
        if (capacity < 2 || capacity > MAX_GROUP_CAPACITY) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "GROUP event types must have capacity between 2 and " + MAX_GROUP_CAPACITY + ".");
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
        EventTypeSummaryResponse.ConferenceResponse conference = new EventTypeSummaryResponse.ConferenceResponse(
                eventType.getConferencingProvider() != ConferencingProviderType.NONE,
                eventType.getConferencingProvider().name(),
                eventType.getCustomConferenceUrl()
        );
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
                eventType.getGroupHostNotificationMode() == null
                        ? GroupHostNotificationMode.SMART_SUMMARY
                        : eventType.getGroupHostNotificationMode(),
                (int) eventType.getDuration().toMinutes(),
                eventType.isPublished(),
                degraded,
                groupSeriesBounds.startDate(),
                groupSeriesBounds.endDate(),
                conference,
                eventType.getKind() == EventKind.GROUP
                        ? EventAvailabilityMode.INHERIT
                        : effectiveAvailabilityMode(eventType),
                summarizeAvailabilityWindows(eventType),
                eventType.getDescription(),
                eventType.getLocation(),
                (int) eventType.getBufferBefore().toMinutes(),
                (int) eventType.getBufferAfter().toMinutes(),
                (int) eventType.getSlotInterval().toMinutes(),
                (int) eventType.getMinNotice().toMinutes(),
                (int) eventType.getMaxAdvance().toDays(),
                (int) eventType.getHoldDuration().toMinutes(),
                eventPaymentConfigService == null ? null : eventPaymentConfigService.response(eventType.getId())
        );
    }

    private static EventAvailabilityMode effectiveAvailabilityMode(EventType eventType) {
        return eventType.getAvailabilityMode() == null
                ? EventAvailabilityMode.INHERIT
                : eventType.getAvailabilityMode();
    }

    /**
     * The event's own custom schedule windows. Empty for GROUP (which reserves time via
     * reservation windows), for INHERIT, and for an explicitly closed CUSTOM schedule.
     * The separate mode field lets clients distinguish the latter two cases.
     */
    private List<EventTypeSummaryResponse.AvailabilityWindowResponse> summarizeAvailabilityWindows(EventType eventType) {
        if (eventType.getKind() == EventKind.GROUP) {
            return List.of();
        }
        return eventAvailabilityWindowRepository.findByEventTypeId(eventType.getId()).stream()
                .map(window -> new EventTypeSummaryResponse.AvailabilityWindowResponse(
                        window.getDayOfWeek().name(),
                        window.getStartTime().toString(),
                        window.getEndTime().toString()))
                .toList();
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
