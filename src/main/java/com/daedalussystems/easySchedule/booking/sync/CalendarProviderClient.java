package com.daedalussystems.easySchedule.booking.sync;

import java.util.UUID;

public interface CalendarProviderClient {
    String createEvent(UUID bookingId, String provider);
}
