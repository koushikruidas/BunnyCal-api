package com.daedalussystems.easySchedule.calendar.client;

import com.daedalussystems.easySchedule.conferencing.service.ConferencingInstruction;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "calendar.provider.mode", havingValue = "in-memory")
public class InMemoryCalendarProviderClient implements CalendarProviderClient {
    private final Map<String, String> eventsByIdempotency = new ConcurrentHashMap<>();
    private final Map<String, String> eventsByExternalId = new ConcurrentHashMap<>();

    @Override
    public CreateEventDetails createEvent(UUID internalId,
                                          String provider,
                                          String idempotencyKey,
                                          ConferencingInstruction conferencingInstruction) {
        String externalId = eventsByIdempotency.computeIfAbsent(
                provider + ":" + idempotencyKey,
                ignored -> provider + "-" + internalId);
        eventsByExternalId.put(externalId, externalId);
        String conferenceUrl = conferencingInstruction != null && conferencingInstruction.embedsExternalUrl()
                ? conferencingInstruction.joinUrl()
                : null;
        return new CreateEventDetails(externalId, null, conferenceUrl);
    }

    @Override
    public String updateEvent(UUID internalId,
                              String provider,
                              String externalEventId,
                              String idempotencyKey,
                              ConferencingInstruction conferencingInstruction) {
        if (externalEventId == null || !eventsByExternalId.containsKey(externalEventId)) {
            throw new CalendarClientException(404, "event not found");
        }
        return externalEventId;
    }

    @Override
    public void deleteEvent(UUID internalId, String provider, String externalEventId) {
        if (externalEventId != null) {
            eventsByExternalId.remove(externalEventId);
        }
    }

    @Override
    public boolean eventExists(UUID internalId, String provider, String externalEventId) {
        return externalEventId != null && eventsByExternalId.containsKey(externalEventId);
    }

    @Override
    public boolean eventMatches(UUID internalId, String provider, String externalEventId, String idempotencyKey) {
        if (!eventExists(internalId, provider, externalEventId)) {
            return false;
        }
        String expectedExternal = eventsByIdempotency.get(provider + ":" + idempotencyKey);
        return externalEventId != null && externalEventId.equals(expectedExternal);
    }
}
