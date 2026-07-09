package io.bunnycal.calendar.client;

import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.util.UUID;
import org.springframework.lang.Nullable;

public interface CalendarProviderClient {
    CalendarProviderType providerType();

    CreateEventDetails createEvent(UUID internalId,
                                   String provider,
                                   String idempotencyKey,
                                   ConferencingInstruction conferencingInstruction,
                                   @Nullable UUID schedulingConnectionId);

    String updateEvent(UUID internalId,
                       String provider,
                       String externalEventId,
                       String idempotencyKey,
                       ConferencingInstruction conferencingInstruction,
                       @Nullable UUID schedulingConnectionId);

    void deleteEvent(UUID internalId, String provider, String externalEventId,
                     @Nullable UUID schedulingConnectionId);

    boolean eventExists(UUID internalId, String provider, String externalEventId,
                        @Nullable UUID schedulingConnectionId);

    boolean eventMatches(UUID internalId, String provider, String externalEventId, String idempotencyKey,
                         @Nullable UUID schedulingConnectionId);

    record CreateEventDetails(String externalEventId,
                              String providerEventUrl,
                              String conferenceUrl) {}
}
