package com.daedalussystems.easySchedule.sync;

import java.util.UUID;

public interface CalendarProviderClient {
    String createEvent(UUID bookingId, String provider, String idempotencyKey);
}
