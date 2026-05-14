package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;
import java.util.List;

public interface ExternalCalendarSyncClient {
    SyncBatch fetchIncremental(CalendarConnection connection, SyncSourceAttribution sourceAttribution)
            throws SyncTokenInvalidException;

    SyncBatch fetchFull(CalendarConnection connection, SyncSourceAttribution sourceAttribution);

    record SyncBatch(
            List<CalendarEventIngestionService.IncomingCalendarEvent> events,
            String nextCursor,
            boolean fullResyncWindow,
            boolean gapSuspected,
            String recoveryAction
    ) {
        public static SyncBatch empty(String nextCursor, boolean fullResyncWindow, String recoveryAction) {
            return new SyncBatch(List.of(), nextCursor, fullResyncWindow, false, recoveryAction);
        }
    }

    class SyncTokenInvalidException extends RuntimeException {
        public SyncTokenInvalidException(String message) {
            super(message);
        }
    }
}
