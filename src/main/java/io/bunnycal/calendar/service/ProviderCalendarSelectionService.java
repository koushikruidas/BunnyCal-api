package io.bunnycal.calendar.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeOrchestrationJsonCodec;
import io.bunnycal.availability.service.EventTypeOrchestrationNormalizer.AvailabilityBinding;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.sync.state.SyncSourceAttribution;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves the set of provider-native calendar IDs that should be polled for
 * availability sync, for a given {@link CalendarConnection}.
 *
 * <p>Source of truth: the user's active event types' {@code availability_calendars_json}.
 * Legacy corruption — bindings where the persisted {@code externalCalendarId} equals the
 * binding's {@code connectionId} (the pre-inventory frontend bug) — is detected, logged
 * once per selection pass, and excluded so a single corrupted row cannot poison the
 * whole sync cycle.
 */
@Service
public class ProviderCalendarSelectionService {
    private static final Logger log = LoggerFactory.getLogger(ProviderCalendarSelectionService.class);

    private final EventTypeRepository eventTypeRepository;
    private final EventTypeOrchestrationJsonCodec orchestrationJsonCodec;

    public ProviderCalendarSelectionService(EventTypeRepository eventTypeRepository,
                                            EventTypeOrchestrationJsonCodec orchestrationJsonCodec) {
        this.eventTypeRepository = eventTypeRepository;
        this.orchestrationJsonCodec = orchestrationJsonCodec;
    }

    /**
     * @return the deduplicated, insertion-ordered list of provider-native calendar IDs
     *         selected for availability on this connection. May be empty when the user
     *         has not yet selected any availability calendars (caller should fall back
     *         to a safe default — e.g. the connection's primary calendar — to avoid a
     *         silently no-op sync).
     */
    public Set<String> selectedAvailabilityCalendarIds(CalendarConnection connection) {
        return selectedAvailabilityCalendarIds(connection, null);
    }

    public Set<String> selectedAvailabilityCalendarIds(CalendarConnection connection,
                                                       SyncSourceAttribution syncMode) {
        if (connection == null || connection.getUserId() == null || connection.getId() == null) {
            return Set.of();
        }
        UUID connectionId = connection.getId();
        String provider = connection.getProvider() == null ? "unknown" : connection.getProvider().name().toLowerCase();
        String syncModeTag = syncMode == null ? "unknown" : syncMode.name();
        Set<String> selected = new LinkedHashSet<>();
        List<EventType> eventTypes = eventTypeRepository.findByUserIdOrderByNameAsc(connection.getUserId());
        for (EventType eventType : eventTypes) {
            String raw = eventType.getAvailabilityCalendarsJson();
            if (raw == null || raw.isBlank()) continue;
            List<AvailabilityBinding> bindings = orchestrationJsonCodec.deserializeAvailabilityBindings(raw);
            for (AvailabilityBinding binding : bindings) {
                if (binding == null || binding.connectionId() == null) continue;
                if (!connectionId.equals(binding.connectionId())) continue;
                String calendarId = binding.externalCalendarId();
                if (calendarId == null || calendarId.isBlank()) continue;
                if ("microsoft".equalsIgnoreCase(provider) && "primary".equalsIgnoreCase(calendarId.trim())) {
                    log.warn("legacy_invalid_calendar_mapping provider={} connectionId={} eventTypeId={} providerCalendarId={} syncMode={} reason=microsoft_primary_alias_not_allowed",
                            provider, connectionId, eventType.getId(), calendarId, syncModeTag);
                    continue;
                }
                if (isLegacyCorruption(connectionId, calendarId)) {
                    log.warn("legacy_invalid_calendar_mapping provider={} connectionId={} eventTypeId={} providerCalendarId={} syncMode={} reason=connection_id_used_as_calendar_id",
                            provider, connectionId, eventType.getId(), calendarId, syncModeTag);
                    continue;
                }
                if (isUuidShaped(calendarId)) {
                    log.warn("legacy_invalid_calendar_mapping provider={} connectionId={} eventTypeId={} providerCalendarId={} syncMode={} reason=uuid_shaped_provider_calendar_id",
                            provider, connectionId, eventType.getId(), calendarId, syncModeTag);
                    continue;
                }
                if (selected.add(calendarId)) {
                    log.info("provider_calendar_selected_for_availability provider={} connectionId={} eventTypeId={} providerCalendarId={} syncMode={} source=availabilityCalendarsJson",
                            provider, connectionId, eventType.getId(), calendarId, syncModeTag);
                }
            }
            if (connectionId.equals(eventType.getProjectionConnectionId())) {
                String projectionCalendarId = eventType.getProjectionCalendarId();
                if (projectionCalendarId != null && !projectionCalendarId.isBlank()) {
                    String trimmed = projectionCalendarId.trim();
                    if ("microsoft".equalsIgnoreCase(provider) && "primary".equalsIgnoreCase(trimmed)) {
                        log.warn("legacy_invalid_calendar_mapping provider={} connectionId={} eventTypeId={} providerCalendarId={} syncMode={} reason=microsoft_projection_primary_alias_not_allowed",
                                provider, connectionId, eventType.getId(), trimmed, syncModeTag);
                        continue;
                    }
                    if (isLegacyCorruption(connectionId, trimmed)) {
                        log.warn("legacy_invalid_calendar_mapping provider={} connectionId={} eventTypeId={} providerCalendarId={} syncMode={} reason=projection_connection_id_used_as_calendar_id",
                                provider, connectionId, eventType.getId(), trimmed, syncModeTag);
                        continue;
                    }
                    if (isUuidShaped(trimmed)) {
                        log.warn("legacy_invalid_calendar_mapping provider={} connectionId={} eventTypeId={} providerCalendarId={} syncMode={} reason=projection_uuid_shaped_provider_calendar_id",
                                provider, connectionId, eventType.getId(), trimmed, syncModeTag);
                        continue;
                    }
                    if (selected.add(trimmed)) {
                        log.info("provider_calendar_selected_for_availability provider={} connectionId={} eventTypeId={} providerCalendarId={} syncMode={} source=projectionDestination.calendarId",
                                provider, connectionId, eventType.getId(), trimmed, syncModeTag);
                    }
                }
            }
        }
        return selected;
    }

    /**
     * Pre-Graph validation: a provider-native calendar ID must not be the connection's
     * own UUID. Microsoft Graph IDs are opaque base64-ish strings and never look like a
     * canonical UUID — this is a precise, no-false-positive check for the legacy bug.
     */
    public static boolean isLegacyCorruption(UUID connectionId, String calendarId) {
        if (connectionId == null || calendarId == null) return false;
        return connectionId.toString().equalsIgnoreCase(calendarId);
    }

    public static boolean isUuidShaped(String calendarId) {
        if (calendarId == null) return false;
        try {
            UUID.fromString(calendarId.trim());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
