package com.daedalussystems.easySchedule.calendar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.auth.TokenRefresher;
import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class GoogleIncrementalSyncObservationClientTest {

    @Mock private GoogleApiClient googleApiClient;
    @Mock private TokenRefresher tokenRefresher;

    private GoogleIncrementalSyncObservationClient client;

    @BeforeEach
    void setUp() {
        client = new GoogleIncrementalSyncObservationClient(
                googleApiClient,
                tokenRefresher,
                new SimpleMeterRegistry(),
                true,
                false,
                168
        );
    }

    @Test
    void fetchIncrementalFallsBackToFullWhenCursorMissing() {
        CalendarConnection connection = connection(null);
        when(tokenRefresher.executeWithValidToken(any(), any())).thenAnswer(inv -> {
            Function<String, GoogleApiClient.SyncWindow> fn = inv.getArgument(1);
            return fn.apply("token");
        });
        when(googleApiClient.listEventsFull("token"))
                .thenReturn(new GoogleApiClient.SyncWindow(List.of(), "next-full"));

        ExternalCalendarSyncClient.SyncBatch batch = client.fetchIncremental(connection, SyncSourceAttribution.WEBHOOK);

        assertEquals("next-full", batch.nextCursor());
        assertEquals(true, batch.fullResyncWindow());
        assertEquals(true, batch.gapSuspected());
    }

    @Test
    void fetchIncrementalThrowsSyncTokenInvalidOn410() {
        CalendarConnection connection = connection("cursor-1");
        when(tokenRefresher.executeWithValidToken(any(), any())).thenThrow(new CalendarClientException(410, "invalid"));

        assertThrows(ExternalCalendarSyncClient.SyncTokenInvalidException.class,
                () -> client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC));
    }

    private static CalendarConnection connection(String cursor) {
        CalendarConnection c = new CalendarConnection();
        c.setProviderSyncCursor(cursor);
        c.setProviderCursorUpdatedAt(Instant.now());
        try {
            var id = CalendarConnection.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(c, UUID.randomUUID());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return c;
    }
}

