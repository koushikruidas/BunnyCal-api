package com.daedalussystems.easySchedule.calendar.client;

import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingInstruction;
import java.util.UUID;

public interface CalendarProviderClient {
    CalendarProviderType providerType();

    CreateEventDetails createEvent(UUID internalId,
                                   String provider,
                                   String idempotencyKey,
                                   ConferencingInstruction conferencingInstruction);

    String updateEvent(UUID internalId,
                       String provider,
                       String externalEventId,
                       String idempotencyKey,
                       ConferencingInstruction conferencingInstruction);

    void deleteEvent(UUID internalId, String provider, String externalEventId);

    boolean eventExists(UUID internalId, String provider, String externalEventId);

    boolean eventMatches(UUID internalId, String provider, String externalEventId, String idempotencyKey);

    record CreateEventDetails(String externalEventId,
                              String providerEventUrl,
                              String conferenceUrl) {}
}
