package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.sync.state.SyncSourceAttribution;
import java.util.List;

public interface ExternalCalendarSyncClient {
    CalendarProviderType provider();

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
