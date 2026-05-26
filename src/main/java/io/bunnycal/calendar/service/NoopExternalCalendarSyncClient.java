package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.sync.state.SyncSourceAttribution;

public class NoopExternalCalendarSyncClient implements ExternalCalendarSyncClient {
    private final CalendarProviderType provider;

    public NoopExternalCalendarSyncClient(CalendarProviderType provider) {
        this.provider = provider;
    }

    @Override
    public CalendarProviderType provider() {
        return provider;
    }

    @Override
    public SyncBatch fetchIncremental(CalendarConnection connection, SyncSourceAttribution sourceAttribution) {
        return SyncBatch.empty(connection == null ? null : connection.getProviderSyncCursor(), false, "noop_incremental");
    }

    @Override
    public SyncBatch fetchFull(CalendarConnection connection, SyncSourceAttribution sourceAttribution) {
        return SyncBatch.empty(connection == null ? null : connection.getProviderSyncCursor(), true, "noop_full");
    }
}
