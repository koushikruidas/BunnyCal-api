package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "calendar.provider.mode", havingValue = "in-memory")
public class NoopExternalCalendarSyncClient implements ExternalCalendarSyncClient {
    @Override
    public SyncBatch fetchIncremental(CalendarConnection connection, SyncSourceAttribution sourceAttribution) {
        return SyncBatch.empty(connection == null ? null : connection.getProviderSyncCursor(), false, "noop_incremental");
    }

    @Override
    public SyncBatch fetchFull(CalendarConnection connection, SyncSourceAttribution sourceAttribution) {
        return SyncBatch.empty(connection == null ? null : connection.getProviderSyncCursor(), true, "noop_full");
    }
}
