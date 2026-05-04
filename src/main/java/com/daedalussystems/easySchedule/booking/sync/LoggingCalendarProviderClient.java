package com.daedalussystems.easySchedule.booking.sync;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LoggingCalendarProviderClient implements CalendarProviderClient {
    @Override
    public String createEvent(UUID bookingId, String provider) {
        return provider + "-" + bookingId;
    }
}
