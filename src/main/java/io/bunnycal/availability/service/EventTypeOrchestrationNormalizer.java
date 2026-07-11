package io.bunnycal.availability.service;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.MicrosoftAccountClassifier;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EventTypeOrchestrationNormalizer {
    private static final Logger log = LoggerFactory.getLogger(EventTypeOrchestrationNormalizer.class);

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final MeterRegistry meterRegistry;

    public EventTypeOrchestrationNormalizer(CalendarConnectionRepository calendarConnectionRepository,
                                            MeterRegistry meterRegistry) {
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.meterRegistry = meterRegistry;
    }

    public NormalizedOrchestration normalize(UUID userId, CreateEventTypeRequest request) {
        List<AvailabilityBinding> availabilityBindings = normalizeAvailabilityBindings(userId, request.availabilityCalendars());
        ConferencingConfig conferencing = normalizeConference(request.conference());

        EventKind kind = request.kind() != null ? request.kind() : EventKind.ONE_ON_ONE;
        ProjectionDestination projectionDestination = kind == EventKind.ROUND_ROBIN
                ? null
                : normalizeProjectionDestination(userId, request.projectionDestination());

        validateConferencingAgainstProjection(conferencing, projectionDestination);
        return new NormalizedOrchestration(projectionDestination, availabilityBindings, conferencing);
    }

    public NormalizedOrchestration normalizeForDraftMutation(UUID userId,
                                                             EventType existingEventType,
                                                             List<CreateEventTypeRequest.AvailabilityCalendarRequest> availabilityCalendars,
                                                             CreateEventTypeRequest.ConferenceRequest conference,
                                                             CreateEventTypeRequest.ProjectionDestinationRequest projectionDestination,
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
        ProjectionDestination effectiveProjection = projectionDestination != null
                ? normalizeProjectionDestination(userId, projectionDestination)
                : projectionDestinationFromExisting(existingEventType, true);
        validateConferencingAgainstProjection(conferencing, effectiveProjection);
        return new NormalizedOrchestration(effectiveProjection, availabilityBindings, conferencing);
    }

    /**
     * Cross-validates conferencing + projection compatibility.
     *
     * Matrix:
     * - GOOGLE_MEET       -> projection provider must be google
     * - MICROSOFT_TEAMS   -> projection provider must be microsoft and account must be M365
     * - ZOOM/CUSTOM_URL   -> projection provider can be google or microsoft
     */
    private void validateConferencingAgainstProjection(ConferencingConfig conferencing,
                                                       ProjectionDestination projection) {
        if (conferencing == null || projection == null) return;
        ConferencingProviderType providerType = conferencing.provider();
        if (providerType == null || providerType == ConferencingProviderType.NONE || providerType == ConferencingProviderType.CUSTOM_URL) {
            return;
        }
        if (providerType == ConferencingProviderType.GOOGLE_MEET
                && !"google".equalsIgnoreCase(projection.provider())) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Google Meet conferencing requires a Google projection calendar.");
        }
        if (providerType != ConferencingProviderType.MICROSOFT_TEAMS) {
            return;
        }
        if (!"microsoft".equalsIgnoreCase(projection.provider())) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Microsoft Teams conferencing requires a Microsoft projection calendar.");
        }
        CalendarConnection connection = calendarConnectionRepository.findById(projection.connectionId())
                .orElse(null);
        if (connection == null) return; // earlier normalization already validated existence
        if (MicrosoftAccountClassifier.isConsumerMsa(connection)) {
            meterRegistry.counter("event_type_validation_rejected_total",
                    "reason", "ms_teams_on_consumer_msa").increment();
            log.warn("event_type_validation_rejected reason=ms_teams_on_consumer_msa connectionId={} providerUserId={}",
                    connection.getId(), connection.getProviderUserId());
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Microsoft Teams conferencing requires a work or school Microsoft account; "
                            + "the chosen projection calendar belongs to a personal Outlook.com account.");
        }
    }

    /**
     * The projection destination is the triple explicitly stored on the event type — there is no
     * fallback resolution from the user's connections, which matters now that a user can hold
     * several accounts per provider and "their calendar" is no longer a single thing. Derived
     * independently of availabilityCalendars, which carries free/busy semantics only.
     */
    private ProjectionDestination projectionDestinationFromExisting(EventType existingEventType, boolean allowMissing) {
        if (existingEventType == null
                || existingEventType.getProjectionProvider() == null
                || existingEventType.getProjectionConnectionId() == null
                || trimToNull(existingEventType.getProjectionCalendarId()) == null) {
            if (allowMissing) {
                return null;
            }
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "projection destination is required and must not use fallback resolution.");
        }
        return new ProjectionDestination(
                existingEventType.getProjectionProvider().name().toLowerCase(Locale.ROOT),
                existingEventType.getProjectionConnectionId(),
                existingEventType.getProjectionCalendarId().trim());
    }

    private List<AvailabilityBinding> normalizeAvailabilityBindings(UUID userId,
                                                                    List<CreateEventTypeRequest.AvailabilityCalendarRequest> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // Dedup on the (connection, calendar) pair, not the connection alone: one account can
        // contribute several calendars to availability, and keying on connectionId would silently
        // drop every calendar after the first. A null externalCalendarId is a whole-connection
        // binding, and still dedups per connection.
        LinkedHashSet<CalendarBindingKey> dedup = new LinkedHashSet<>();
        List<AvailabilityBinding> bindings = new ArrayList<>();
        for (CreateEventTypeRequest.AvailabilityCalendarRequest entry : raw) {
            UUID connectionId = parseConnectionId(entry);
            if (connectionId == null) {
                continue;
            }
            String externalCalendarId = trimToNull(entry.externalCalendarId());
            if (!dedup.add(new CalendarBindingKey(connectionId, externalCalendarId))) {
                continue;
            }
            CalendarConnection connection = calendarConnectionRepository.findById(connectionId)
                    .filter(c -> userId.equals(c.getUserId()))
                    .filter(c -> c.getStatus() == CalendarConnectionStatus.ACTIVE)
                    .orElseThrow(() -> new CustomException(ErrorCode.VALIDATION_ERROR, "availability calendar connection is invalid."));
            if (externalCalendarId != null
                    && (isUuidShaped(externalCalendarId)
                    || connection.getId().toString().equalsIgnoreCase(externalCalendarId))) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "availabilityCalendars[].externalCalendarId must be a provider calendar id, not a UUID/connectionId.");
            }
            bindings.add(new AvailabilityBinding(connection.getId(), connection.getProvider().name().toLowerCase(Locale.ROOT), externalCalendarId));
        }
        return List.copyOf(bindings);
    }

    private record CalendarBindingKey(UUID connectionId, String externalCalendarId) {}

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

    private static boolean isUuidShaped(String value) {
        if (value == null) return false;
        try {
            UUID.fromString(value.trim());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private ProjectionDestination normalizeProjectionDestination(UUID userId,
                                                                 CreateEventTypeRequest.ProjectionDestinationRequest projectionDestination) {
        if (projectionDestination == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "projectionDestination is required.");
        }
        String providerRaw = trimToNull(projectionDestination.provider());
        String connectionIdRaw = trimToNull(projectionDestination.connectionId());
        String calendarId = trimToNull(projectionDestination.calendarId());
        if (providerRaw == null || connectionIdRaw == null || calendarId == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "projectionDestination.provider, projectionDestination.connectionId and projectionDestination.calendarId are required.");
        }
        UUID connectionId;
        try {
            connectionId = UUID.fromString(connectionIdRaw);
        } catch (IllegalArgumentException ex) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "projectionDestination.connectionId must be a valid UUID.");
        }
        CalendarProviderType providerType;
        try {
            providerType = CalendarProviderType.valueOf(providerRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "projectionDestination.provider is invalid.");
        }
        CalendarConnection connection = calendarConnectionRepository.findById(connectionId)
                .filter(c -> userId.equals(c.getUserId()))
                .filter(c -> c.getStatus() == CalendarConnectionStatus.ACTIVE)
                .orElseThrow(() -> new CustomException(ErrorCode.VALIDATION_ERROR, "projection destination connection is invalid."));
        if (isUuidShaped(calendarId) || connectionId.toString().equalsIgnoreCase(calendarId)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "projectionDestination.calendarId must be a provider calendar id, not a UUID/connectionId.");
        }
        if (connection.getProvider() != providerType) {
            meterRegistry.counter("ownership_resolution_failures_total", "reason", "provider_mismatch").increment();
            log.warn("projection_destination_validation_failed userId={} provider={} connectionId={} calendarId={} reason=provider_mismatch",
                    userId, providerType, connectionId, calendarId);
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "projection destination provider does not match connection provider.");
        }
        if (!hasWriteScope(connection)) {
            meterRegistry.counter("ownership_resolution_failures_total", "reason", "missing_write_scope").increment();
            log.warn("projection_destination_validation_failed userId={} provider={} connectionId={} calendarId={} reason=missing_write_scope",
                    userId, providerType, connectionId, calendarId);
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "projection destination connection lacks writable calendar scope.");
        }
        log.info("projection_destination_resolved userId={} provider={} connectionId={} calendarId={} source=explicit_request",
                userId, providerType, connectionId, calendarId);
        return new ProjectionDestination(providerType.name().toLowerCase(Locale.ROOT), connectionId, calendarId);
    }

    private static boolean hasWriteScope(CalendarConnection connection) {
        if (connection.getScopes() == null || connection.getScopes().isEmpty()) {
            return false;
        }
        return connection.getScopes().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(scope -> !scope.contains("readonly"))
                .anyMatch(scope -> scope.contains("readwrite")
                        || scope.contains("calendar.events")
                        || scope.contains("calendars")
                        || scope.contains("calendar"));
    }

    public record AvailabilityBinding(UUID connectionId, String provider, String externalCalendarId) {}
    public record ConferencingConfig(boolean enabled, ConferencingProviderType provider, String customUrl) {}
    public record ProjectionDestination(String provider, UUID connectionId, String calendarId) {}

    public record NormalizedOrchestration(ProjectionDestination projectionDestination,
                                          List<AvailabilityBinding> availabilityBindings,
                                          ConferencingConfig conferencing) {}
}
