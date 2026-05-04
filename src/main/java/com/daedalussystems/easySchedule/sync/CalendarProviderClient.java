package com.daedalussystems.easySchedule.calendar.client;

import java.util.UUID;

public interface CalendarProviderClient {
    String createEvent(UUID bookingId, String provider, String idempotencyKey);
}
