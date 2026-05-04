package com.daedalussystems.easySchedule.calendar.client;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class LoggingCalendarProviderClient implements CalendarProviderClient {
    private final ConcurrentMap<String, String> eventsByKey = new ConcurrentHashMap<>();

    @Override
    public String createEvent(UUID bookingId, String provider, String idempotencyKey) {
        return eventsByKey.computeIfAbsent(idempotencyKey, ignored -> provider + "-" + bookingId);
    }
}
