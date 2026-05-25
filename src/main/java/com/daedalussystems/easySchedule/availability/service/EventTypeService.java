package com.daedalussystems.easySchedule.availability.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.AvailabilityMode;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.dto.CreateEventTypeRequest;
import com.daedalussystems.easySchedule.availability.dto.EventTypeSummaryResponse;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventTypeService {
    private final EventTypeRepository eventTypeRepository;
    private final UserRepository userRepository;
    private final EventTypeOrchestrationNormalizer orchestrationNormalizer;
    private final EventTypeOrchestrationJsonCodec orchestrationJsonCodec;

    public EventTypeService(EventTypeRepository eventTypeRepository,
                            UserRepository userRepository,
                            EventTypeOrchestrationNormalizer orchestrationNormalizer,
                            EventTypeOrchestrationJsonCodec orchestrationJsonCodec) {
        this.eventTypeRepository = eventTypeRepository;
        this.userRepository = userRepository;
        this.orchestrationNormalizer = orchestrationNormalizer;
        this.orchestrationJsonCodec = orchestrationJsonCodec;
    }

    @Transactional
    public EventTypeSummaryResponse create(UUID userId, CreateEventTypeRequest request) {
        validate(request);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));

        String username = ensureUsername(user);
        String slug = uniqueSlug(userId, requestedOrDerivedSlug(request));
        EventTypeOrchestrationNormalizer.NormalizedOrchestration orchestration = orchestrationNormalizer.normalize(userId, request);

        AvailabilityMode availabilityMode = (request.availabilityCalendars() != null && !request.availabilityCalendars().isEmpty())
                ? AvailabilityMode.SELECTED
                : AvailabilityMode.ALL_CONNECTED;

        EventType eventType = EventType.builder()
                .userId(userId)
                .name(request.name().trim())
                .description(trimToNull(request.description()))
                .location(trimToNull(request.location()))
                .organizerCalendarConnectionId(orchestration.syncConnectionId())
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
                .build();

        EventType saved = eventTypeRepository.save(eventType);
        return toSummary(saved, username);
    }

    @Transactional(readOnly = true)
    public List<EventTypeSummaryResponse> list(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
        String username = user.getUsername() != null ? user.getUsername() : fallbackUsername(user.getId());

        return eventTypeRepository.findByUserIdOrderByNameAsc(userId).stream()
                .map(et -> toSummary(et, username))
                .toList();
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
        return new EventTypeSummaryResponse(
                eventType.getId(),
                eventType.getName(),
                eventType.getSlug(),
                "/public/" + username + "/" + eventType.getSlug(),
                availability,
                conference
        );
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
