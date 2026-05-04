package com.daedalussystems.easySchedule.calendar.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class InMemoryCalendarProviderClient implements CalendarProviderClient {
    private final Map<String, String> eventsByIdempotency = new ConcurrentHashMap<>();
    private final Map<String, String> eventsByExternalId = new ConcurrentHashMap<>();

    @Override
    public String createEvent(UUID internalId, String provider, String idempotencyKey) {
        String externalId = eventsByIdempotency.computeIfAbsent(
                provider + ":" + idempotencyKey,
                ignored -> provider + "-" + internalId);
        eventsByExternalId.put(externalId, externalId);
        return externalId;
    }

    @Override
    public String updateEvent(UUID internalId, String provider, String externalEventId, String idempotencyKey) {
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
