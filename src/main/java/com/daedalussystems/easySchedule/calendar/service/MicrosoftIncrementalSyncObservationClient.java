package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.auth.TokenRefresher;
import com.daedalussystems.easySchedule.calendar.client.MicrosoftApiClient;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "calendar.provider.mode", havingValue = "microsoft")
public class MicrosoftIncrementalSyncObservationClient implements ExternalCalendarSyncClient {
    private final MicrosoftApiClient microsoftApiClient;
    private final TokenRefresher tokenRefresher;
    private final MeterRegistry meterRegistry;

    public MicrosoftIncrementalSyncObservationClient(MicrosoftApiClient microsoftApiClient,
                                                     TokenRefresher tokenRefresher,
                                                     MeterRegistry meterRegistry) {
        this.microsoftApiClient = microsoftApiClient;
        this.tokenRefresher = tokenRefresher;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public SyncBatch fetchIncremental(CalendarConnection connection, SyncSourceAttribution sourceAttribution) throws SyncTokenInvalidException {
        String cursor = normalize(connection.getProviderSyncCursor());
        if (cursor == null) {
            SyncBatch full = fetchFull(connection, sourceAttribution);
            return new SyncBatch(full.events(), full.nextCursor(), true, true, "missing_cursor_full_recovery");
        }
        MicrosoftApiClient.SyncWindow window = tokenRefresher.executeWithValidToken(
                connection.getId(),
                token -> microsoftApiClient.listEventsIncremental(token, cursor));
        meterRegistry.counter("calendar.sync.incremental.total", "provider", "microsoft").increment();
        return new SyncBatch(toIncoming(window.events()), normalize(window.nextDeltaCursor()), false, false, "incremental");
    }

    @Override
    public SyncBatch fetchFull(CalendarConnection connection, SyncSourceAttribution sourceAttribution) {
        MicrosoftApiClient.SyncWindow window = tokenRefresher.executeWithValidToken(
                connection.getId(),
                microsoftApiClient::listEventsFull);
        meterRegistry.counter("calendar.sync.incremental_recovery.total", "provider", "microsoft").increment();
        return new SyncBatch(toIncoming(window.events()), normalize(window.nextDeltaCursor()), true, false, "full_recovery");
    }

    private static List<CalendarEventIngestionService.IncomingCalendarEvent> toIncoming(List<MicrosoftApiClient.CalendarEventObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }
        return observations.stream()
                .map(obs -> new CalendarEventIngestionService.IncomingCalendarEvent(
                        obs.externalEventId(),
                        obs.startsAt() == null ? Instant.EPOCH : obs.startsAt(),
                        obs.endsAt() == null ? Instant.EPOCH.plusSeconds(60) : obs.endsAt(),
                        obs.cancelled(),
                        obs.providerSequence(),
                        obs.providerUpdatedAt(),
                        obs.providerEtag(),
                        obs.payloadHash()))
                .toList();
    }

    private static String normalize(String token) {
        if (token == null) {
            return null;
        }
        String trimmed = token.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
