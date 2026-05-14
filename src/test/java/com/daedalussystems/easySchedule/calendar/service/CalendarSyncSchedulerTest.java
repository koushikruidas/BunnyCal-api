package com.daedalussystems.easySchedule.calendar.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarSyncSchedulerTest {

    @Mock
    private CalendarConnectionRepository connectionRepository;
    @Mock
    private CalendarEventIngestionService ingestionService;
    @Mock
    private ExternalCalendarSyncClient syncClient;
    @Mock
    private SlotCacheVersionService slotCacheVersionService;
    @Mock
    private CalendarConnectionWriteService connectionWriteService;

    private CalendarSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CalendarSyncScheduler(
                connectionRepository,
                ingestionService,
                syncClient,
                slotCacheVersionService,
                connectionWriteService,
                new SimpleMeterRegistry());
    }

    @Test
    void sync_includesFailedAndErrorConnectionsAndMarksActiveOnSuccess() {
        CalendarConnection failed = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.FAILED);
        CalendarConnection errored = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ERROR);

        when(connectionRepository.findByStatus(CalendarConnectionStatus.ACTIVE)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.SYNCING)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.FAILED)).thenReturn(List.of(failed));
        when(connectionRepository.findByStatus(CalendarConnectionStatus.ERROR)).thenReturn(List.of(errored));
        when(syncClient.fetchIncremental(any(), org.mockito.ArgumentMatchers.eq(SyncSourceAttribution.PULL_SYNC)))
                .thenReturn(new ExternalCalendarSyncClient.SyncBatch(List.of(), "cursor-1", false, false, "incremental"));

        scheduler.sync();

        verify(syncClient, times(2)).fetchIncremental(any(), org.mockito.ArgumentMatchers.eq(SyncSourceAttribution.PULL_SYNC));
        verify(connectionWriteService, times(2)).markActive(any(), any(), any(), any());
    }

    private static CalendarConnection connection(UUID id, UUID userId, CalendarConnectionStatus status) {
        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(userId);
        connection.setStatus(status);
        connection.setLastTokenExpiresAt(Instant.now().plusSeconds(600));
        connection.setLastSyncedAt(Instant.now());
        try {
            java.lang.reflect.Field idField = CalendarConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(connection, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return connection;
    }
}
