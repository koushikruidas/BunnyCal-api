package io.bunnycal.calendar.client;

import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.lang.Nullable;

public class InMemoryCalendarProviderClient implements CalendarProviderClient {
    private final CalendarProviderType providerType;
    private final Map<String, String> eventsByIdempotency = new ConcurrentHashMap<>();
    private final Map<String, String> eventsByExternalId = new ConcurrentHashMap<>();

    public InMemoryCalendarProviderClient(CalendarProviderType providerType) {
        this.providerType = providerType;
    }

    @Override
    public CalendarProviderType providerType() {
        return providerType;
    }

    @Override
    public CreateEventDetails createEvent(UUID internalId,
                                          String provider,
                                          String idempotencyKey,
                                          ConferencingInstruction conferencingInstruction,
                                          @Nullable UUID schedulingConnectionId) {
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
                              ConferencingInstruction conferencingInstruction,
                              @Nullable UUID schedulingConnectionId) {
        if (externalEventId == null || !eventsByExternalId.containsKey(externalEventId)) {
            throw new CalendarClientException(404, "event not found");
        }
        return externalEventId;
    }

    @Override
    public void deleteEvent(UUID internalId, String provider, String externalEventId,
                            @Nullable UUID schedulingConnectionId) {
        if (externalEventId != null) {
            eventsByExternalId.remove(externalEventId);
        }
    }

    @Override
    public boolean eventExists(UUID internalId, String provider, String externalEventId,
                               @Nullable UUID schedulingConnectionId) {
        return externalEventId != null && eventsByExternalId.containsKey(externalEventId);
    }

    @Override
    public boolean eventMatches(UUID internalId, String provider, String externalEventId, String idempotencyKey,
                                @Nullable UUID schedulingConnectionId) {
        if (!eventExists(internalId, provider, externalEventId, schedulingConnectionId)) {
            return false;
        }
        String expectedExternal = eventsByIdempotency.get(provider + ":" + idempotencyKey);
        return externalEventId != null && externalEventId.equals(expectedExternal);
    }
}
