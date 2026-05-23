package com.daedalussystems.easySchedule.availability.service;

import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.dto.CreateEventTypeRequest;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
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
        UUID authoritativeConnectionId = resolveAuthoritativeConnectionId(
                userId,
                request.organizerCalendarConnectionId(),
                request.orchestrationProvider(),
                request.calendarProvider(),
                null);
        if (authoritativeConnectionId == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "organizerCalendarConnectionId is required.");
        }
        return normalizeResolved(userId, authoritativeConnectionId, request.availabilityCalendars(),
                request.conference(), request.conferencingProvider(), request.customConferenceUrl());
    }

    public NormalizedOrchestration normalizeForDraftMutation(UUID userId,
                                                             EventType existingEventType,
                                                             UUID organizerCalendarConnectionId,
                                                             String orchestrationProvider,
                                                             String calendarProvider,
                                                             List<CreateEventTypeRequest.AvailabilityCalendarRequest> availabilityCalendars,
                                                             CreateEventTypeRequest.ConferenceRequest conference,
                                                             String conferencingProvider,
                                                             String customConferenceUrl,
                                                             List<AvailabilityBinding> existingAvailabilityBindings,
                                                             boolean allowMissingAuthoritativeConnection) {
        UUID authoritativeConnectionId = resolveAuthoritativeConnectionId(
                userId,
                organizerCalendarConnectionId,
                orchestrationProvider,
                calendarProvider,
                existingEventType == null ? null : existingEventType.getOrganizerCalendarConnectionId());
        if (authoritativeConnectionId == null && !allowMissingAuthoritativeConnection) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "organizerCalendarConnectionId is required.");
        }

        List<AvailabilityBinding> availabilityBindings = availabilityCalendars != null
                ? normalizeAvailabilityBindings(userId, availabilityCalendars)
                : (existingAvailabilityBindings == null ? List.of() : List.copyOf(existingAvailabilityBindings));

        CreateEventTypeRequest.ConferenceRequest effectiveConference = conference;
        String effectiveConferencingProvider = conferencingProvider;
        String effectiveCustomConferenceUrl = customConferenceUrl;
        if (effectiveConference == null && effectiveConferencingProvider == null && effectiveCustomConferenceUrl == null && existingEventType != null) {
            boolean enabled = existingEventType.getConferencingProvider() != ConferencingProviderType.NONE;
            effectiveConference = new CreateEventTypeRequest.ConferenceRequest(
                    enabled,
                    existingEventType.getConferencingProvider().name(),
                    existingEventType.getCustomConferenceUrl()
            );
        }
        ConferencingConfig conferencing = normalizeConference(effectiveConference, effectiveConferencingProvider, effectiveCustomConferenceUrl);
        if (authoritativeConnectionId == null) {
            return new NormalizedOrchestration(null, null, availabilityBindings, conferencing);
        }
        CalendarConnection authoritative = requireActiveOwnedConnection(userId, authoritativeConnectionId, "authoritative scheduling connection is invalid.");
        return new NormalizedOrchestration(authoritativeConnectionId, authoritative.getProvider(), availabilityBindings, conferencing);
    }

    private NormalizedOrchestration normalizeResolved(UUID userId,
                                                      UUID authoritativeConnectionId,
                                                      List<CreateEventTypeRequest.AvailabilityCalendarRequest> availabilityCalendars,
                                                      CreateEventTypeRequest.ConferenceRequest conference,
                                                      String conferencingProvider,
                                                      String customConferenceUrl) {
        CalendarConnection authoritative = requireActiveOwnedConnection(
                userId, authoritativeConnectionId, "authoritative scheduling connection is invalid.");

        List<AvailabilityBinding> availabilityBindings = normalizeAvailabilityBindings(userId, availabilityCalendars);
        ConferencingConfig conferencing = normalizeConference(conference, conferencingProvider, customConferenceUrl);
        return new NormalizedOrchestration(authoritativeConnectionId, authoritative.getProvider(), availabilityBindings, conferencing);
    }

    private UUID resolveAuthoritativeConnectionId(UUID userId,
                                                  UUID organizerCalendarConnectionId,
                                                  String orchestrationProvider,
                                                  String calendarProvider,
                                                  UUID fallbackConnectionId) {
        if (organizerCalendarConnectionId != null) {
            return organizerCalendarConnectionId;
        }
        String provider = trimToNull(orchestrationProvider);
        if (provider == null) {
            provider = trimToNull(calendarProvider);
        }
        if (provider == null) {
            return fallbackConnectionId;
        }
        CalendarProviderType providerType = parseCalendarProvider(provider, "invalid calendarProvider");
        return calendarConnectionRepository.findByUserIdAndProviderAndStatus(userId, providerType, CalendarConnectionStatus.ACTIVE)
                .map(CalendarConnection::getId)
                .orElse(fallbackConnectionId);
    }

    private List<AvailabilityBinding> normalizeAvailabilityBindings(UUID userId,
                                                                    List<CreateEventTypeRequest.AvailabilityCalendarRequest> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> dedup = new LinkedHashSet<>();
        List<AvailabilityBinding> bindings = new ArrayList<>();
        for (CreateEventTypeRequest.AvailabilityCalendarRequest entry : raw) {
            if (entry == null || entry.connectionId() == null || !dedup.add(entry.connectionId())) {
                continue;
            }
            CalendarConnection connection = calendarConnectionRepository.findById(entry.connectionId())
                    .filter(c -> userId.equals(c.getUserId()))
                    .filter(c -> c.getStatus() == CalendarConnectionStatus.ACTIVE)
                    .orElseThrow(() -> new CustomException(ErrorCode.VALIDATION_ERROR, "availability calendar connection is invalid."));
            bindings.add(new AvailabilityBinding(connection.getId(), connection.getProvider().name().toLowerCase(Locale.ROOT), trimToNull(entry.externalCalendarId())));
        }
        return List.copyOf(bindings);
    }

    private ConferencingConfig normalizeConference(CreateEventTypeRequest.ConferenceRequest conference,
                                                   String legacyConferencingProvider,
                                                   String legacyCustomConferenceUrl) {
        boolean enabled = conference != null && Boolean.TRUE.equals(conference.enabled());
        String providerRaw = conference != null ? conference.provider() : legacyConferencingProvider;
        String customUrl = trimToNull(conference != null ? conference.customUrl() : legacyCustomConferenceUrl);

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

    private CalendarConnection requireActiveOwnedConnection(UUID userId, UUID connectionId, String message) {
        return calendarConnectionRepository.findById(connectionId)
                .filter(connection -> userId.equals(connection.getUserId()))
                .filter(connection -> connection.getStatus() == CalendarConnectionStatus.ACTIVE)
                .orElseThrow(() -> new CustomException(ErrorCode.VALIDATION_ERROR, message));
    }

    private static CalendarProviderType parseCalendarProvider(String raw, String message) {
        try {
            return CalendarProviderType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, message);
        }
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
    public record NormalizedOrchestration(UUID authoritativeConnectionId,
                                          CalendarProviderType authoritativeProvider,
                                          List<AvailabilityBinding> availabilityBindings,
                                          ConferencingConfig conferencing) {}
}
