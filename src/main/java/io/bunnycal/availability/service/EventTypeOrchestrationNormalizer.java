package io.bunnycal.availability.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class EventTypeOrchestrationNormalizer {

    private final CalendarConnectionRepository calendarConnectionRepository;

    public EventTypeOrchestrationNormalizer(CalendarConnectionRepository calendarConnectionRepository) {
        this.calendarConnectionRepository = calendarConnectionRepository;
    }

    public NormalizedOrchestration normalize(UUID userId, CreateEventTypeRequest request) {
        List<AvailabilityBinding> availabilityBindings = normalizeAvailabilityBindings(userId, request.availabilityCalendars());
        ConferencingConfig conferencing = normalizeConference(request.conference());
        UUID syncConnectionId = resolveSyncConnectionId(userId);
        return new NormalizedOrchestration(syncConnectionId, availabilityBindings, conferencing);
    }

    public NormalizedOrchestration normalizeForDraftMutation(UUID userId,
                                                             EventType existingEventType,
                                                             List<CreateEventTypeRequest.AvailabilityCalendarRequest> availabilityCalendars,
                                                             CreateEventTypeRequest.ConferenceRequest conference,
                                                             List<AvailabilityBinding> existingAvailabilityBindings) {
        List<AvailabilityBinding> availabilityBindings = availabilityCalendars != null
                ? normalizeAvailabilityBindings(userId, availabilityCalendars)
                : (existingAvailabilityBindings == null ? List.of() : List.copyOf(existingAvailabilityBindings));

        CreateEventTypeRequest.ConferenceRequest effectiveConference = conference;
        if (effectiveConference == null && existingEventType != null) {
            boolean enabled = existingEventType.getConferencingProvider() != ConferencingProviderType.NONE;
            effectiveConference = new CreateEventTypeRequest.ConferenceRequest(
                    enabled,
                    existingEventType.getConferencingProvider().name(),
                    existingEventType.getCustomConferenceUrl()
            );
        }
        ConferencingConfig conferencing = normalizeConference(effectiveConference);
        UUID syncConnectionId = resolveSyncConnectionId(userId);
        return new NormalizedOrchestration(syncConnectionId, availabilityBindings, conferencing);
    }

    /**
     * Sync/mirror connection = oldest active connection by creation time.
     * Derived independently of availabilityCalendars ordering — availability is free/busy semantics only.
     */
    private UUID resolveSyncConnectionId(UUID userId) {
        return calendarConnectionRepository
                .findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE)
                .stream()
                .findFirst()
                .map(CalendarConnection::getId)
                .orElse(null);
    }

    private List<AvailabilityBinding> normalizeAvailabilityBindings(UUID userId,
                                                                    List<CreateEventTypeRequest.AvailabilityCalendarRequest> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> dedup = new LinkedHashSet<>();
        List<AvailabilityBinding> bindings = new ArrayList<>();
        for (CreateEventTypeRequest.AvailabilityCalendarRequest entry : raw) {
            UUID connectionId = parseConnectionId(entry);
            if (connectionId == null || !dedup.add(connectionId)) {
                continue;
            }
            CalendarConnection connection = calendarConnectionRepository.findById(connectionId)
                    .filter(c -> userId.equals(c.getUserId()))
                    .filter(c -> c.getStatus() == CalendarConnectionStatus.ACTIVE)
                    .orElseThrow(() -> new CustomException(ErrorCode.VALIDATION_ERROR, "availability calendar connection is invalid."));
            bindings.add(new AvailabilityBinding(connection.getId(), connection.getProvider().name().toLowerCase(Locale.ROOT), trimToNull(entry.externalCalendarId())));
        }
        return List.copyOf(bindings);
    }

    private static UUID parseConnectionId(CreateEventTypeRequest.AvailabilityCalendarRequest entry) {
        if (entry == null || entry.connectionId() == null || entry.connectionId().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(entry.connectionId().trim());
        } catch (IllegalArgumentException ex) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "availabilityCalendars[].connectionId must be a valid UUID.");
        }
    }

    private ConferencingConfig normalizeConference(CreateEventTypeRequest.ConferenceRequest conference) {
        boolean enabled = conference != null && Boolean.TRUE.equals(conference.enabled());
        String providerRaw = conference != null ? conference.provider() : null;
        String customUrl = trimToNull(conference != null ? conference.customUrl() : null);

        ConferencingProviderType providerType;
        if (!enabled) {
            providerType = ConferencingProviderType.NONE;
            customUrl = null;
        } else if (providerRaw == null || providerRaw.isBlank()) {
            providerType = ConferencingProviderType.GOOGLE_MEET;
        } else {
            providerType = parseConferencingProvider(providerRaw);
        }

        if (providerType == ConferencingProviderType.CUSTOM_URL) {
            if (customUrl == null || !isValidHttpsUrl(customUrl)) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR, "custom conference URL must be a valid https URL.");
            }
        } else {
            customUrl = null;
        }
        return new ConferencingConfig(enabled, providerType, customUrl);
    }

    private static ConferencingProviderType parseConferencingProvider(String raw) {
        try {
            ConferencingProviderType type = ConferencingProviderType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            if (type == ConferencingProviderType.NONE) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR, "conference.provider cannot be NONE when conference.enabled=true.");
            }
            return type;
        } catch (IllegalArgumentException ex) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "invalid conference.provider");
        }
    }

    private static boolean isValidHttpsUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record AvailabilityBinding(UUID connectionId, String provider, String externalCalendarId) {}
    public record ConferencingConfig(boolean enabled, ConferencingProviderType provider, String customUrl) {}
    /** syncConnectionId = oldest active connection — used internally for mirror/calendar writes. Independent of availabilityCalendars ordering. */
    public record NormalizedOrchestration(UUID syncConnectionId,
                                          List<AvailabilityBinding> availabilityBindings,
                                          ConferencingConfig conferencing) {}
}
