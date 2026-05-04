package com.daedalussystems.easySchedule.calendar.client;

import java.util.UUID;

public interface CalendarProviderClient {
    String createEvent(UUID internalId, String provider, String idempotencyKey);

    String updateEvent(UUID internalId, String provider, String externalEventId, String idempotencyKey);

    void deleteEvent(UUID internalId, String provider, String externalEventId);

    boolean eventExists(UUID internalId, String provider, String externalEventId);
}
