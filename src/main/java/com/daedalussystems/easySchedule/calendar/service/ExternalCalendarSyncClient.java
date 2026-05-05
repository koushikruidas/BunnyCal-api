package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import java.util.List;

public interface ExternalCalendarSyncClient {
    List<CalendarEventIngestionService.IncomingCalendarEvent> fetchIncremental(CalendarConnection connection)
            throws SyncTokenInvalidException;

    List<CalendarEventIngestionService.IncomingCalendarEvent> fetchFull(CalendarConnection connection);

    class SyncTokenInvalidException extends RuntimeException {
        public SyncTokenInvalidException(String message) {
            super(message);
        }
    }
}
