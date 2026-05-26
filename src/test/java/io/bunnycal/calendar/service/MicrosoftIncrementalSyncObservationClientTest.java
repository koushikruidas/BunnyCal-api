package io.bunnycal.calendar.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.MicrosoftApiClient;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MicrosoftIncrementalSyncObservationClientTest {

    @Mock private MicrosoftApiClient microsoftApiClient;
    @Mock private TokenRefresher tokenRefresher;

    private MicrosoftIncrementalSyncObservationClient client;

    @BeforeEach
    void setUp() {
        client = new MicrosoftIncrementalSyncObservationClient(
                microsoftApiClient,
                tokenRefresher,
                new SimpleMeterRegistry());
    }

    @Test
    void fetchIncrementalThrowsSyncTokenInvalidWhenCursorLooksCorrupted() {
        CalendarConnection connection = connection("https://graph.microsoft.com/v1.0/me/calendar/events?%2525252524top=10");

        assertThrows(ExternalCalendarSyncClient.SyncTokenInvalidException.class,
                () -> client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC));
    }

    @Test
    void fetchIncrementalThrowsSyncTokenInvalidWhenProviderRejectsOversizedHeaders() {
        CalendarConnection connection = connection("https://graph.microsoft.com/v1.0/me/calendar/events?$deltatoken=abc");
        when(tokenRefresher.executeWithValidToken(any(), any()))
                .thenThrow(new CalendarClientException(400, "HTTP Error 400. The size of the request headers is too long."));

        assertThrows(ExternalCalendarSyncClient.SyncTokenInvalidException.class,
                () -> client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC));
    }

    @Test
    void corruptedCursorHeuristicMatchesObservedFailurePattern() {
        assertTrue(MicrosoftIncrementalSyncObservationClient.isLikelyCorruptedCursor(
                "https://graph.microsoft.com/v1.0/me/calendar/events?%2525252525252524top=10"));
        assertFalse(MicrosoftIncrementalSyncObservationClient.isLikelyCorruptedCursor(
                "https://graph.microsoft.com/v1.0/me/calendar/events/delta?$select=id,start,end"));
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

