package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class NoopExternalCalendarSyncClient implements ExternalCalendarSyncClient {
    @Override
    public List<CalendarEventIngestionService.IncomingCalendarEvent> fetchIncremental(CalendarConnection connection) {
        return List.of();
    }

    @Override
    public List<CalendarEventIngestionService.IncomingCalendarEvent> fetchFull(CalendarConnection connection) {
        return List.of();
    }
}
