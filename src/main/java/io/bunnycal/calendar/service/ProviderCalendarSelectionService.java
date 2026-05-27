package io.bunnycal.calendar.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeOrchestrationJsonCodec;
import io.bunnycal.availability.service.EventTypeOrchestrationNormalizer.AvailabilityBinding;
import io.bunnycal.calendar.domain.CalendarConnection;
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
        if (connection == null || connection.getUserId() == null || connection.getId() == null) {
            return Set.of();
        }
        UUID connectionId = connection.getId();
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
                if (isLegacyCorruption(connectionId, calendarId)) {
                    log.warn("legacy_invalid_calendar_mapping connectionId={} calendarId={} reason=uuid_instead_of_provider_calendar_id eventTypeId={}",
                            connectionId, calendarId, eventType.getId());
                    continue;
                }
                if (selected.add(calendarId)) {
                    // Provider-neutral log name; the actual provider is on the connection.
                    // Legacy emitters used `microsoft_calendar_selected_for_availability`
                    // which was provider-specific and made Google selection invisible.
                    log.info("provider_calendar_selected_for_availability provider={} connectionId={} calendarId={} source=availabilityCalendarsJson",
                            binding.provider() == null ? "unknown" : binding.provider(),
                            connectionId, calendarId);
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
}
