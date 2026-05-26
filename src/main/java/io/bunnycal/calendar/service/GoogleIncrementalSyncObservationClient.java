package io.bunnycal.calendar.service;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.GoogleApiClient;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GoogleIncrementalSyncObservationClient implements ExternalCalendarSyncClient {
    private final GoogleApiClient googleApiClient;
    private final TokenRefresher tokenRefresher;
    private final MeterRegistry meterRegistry;
    private final boolean incrementalEnabled;
    private final boolean shadowMode;
    private final Duration staleCursorMaxAge;

    public GoogleIncrementalSyncObservationClient(GoogleApiClient googleApiClient,
                                                  TokenRefresher tokenRefresher,
                                                  MeterRegistry meterRegistry,
                                                  @Value("${calendar.sync.incremental.enabled:false}") boolean incrementalEnabled,
                                                  @Value("${calendar.sync.incremental.shadow-mode:true}") boolean shadowMode,
                                                  @Value("${calendar.sync.incremental.stale-cursor-max-age-hours:168}") long staleCursorMaxAgeHours) {
        this.googleApiClient = googleApiClient;
        this.tokenRefresher = tokenRefresher;
        this.meterRegistry = meterRegistry;
        this.incrementalEnabled = incrementalEnabled;
        this.shadowMode = shadowMode;
        this.staleCursorMaxAge = Duration.ofHours(Math.max(1, staleCursorMaxAgeHours));
    }

    @Override
    public CalendarProviderType provider() {
        return CalendarProviderType.GOOGLE;
    }

    @Override
    public SyncBatch fetchIncremental(CalendarConnection connection, SyncSourceAttribution sourceAttribution)
            throws SyncTokenInvalidException {
        String cursor = normalize(connection.getProviderSyncCursor());
        if (!incrementalEnabled) {
            meterRegistry.counter("calendar.sync.incremental.disabled.total", "provider", "google", "source", source(sourceAttribution))
                    .increment();
            return SyncBatch.empty(cursor, false, "incremental_disabled");
        }
        if (cursor == null) {
            meterRegistry.counter("calendar.sync.webhook_gap_suspected.total",
                            "provider", "google",
                            "source", source(sourceAttribution),
                            "action", "missing_cursor_full_recovery")
                    .increment();
            SyncBatch full = fetchFull(connection, sourceAttribution);
            return new SyncBatch(full.events(), full.nextCursor(), true, true, "missing_cursor_full_recovery");
        }
        if (isStale(connection.getProviderCursorUpdatedAt())) {
            meterRegistry.counter("calendar.sync.stale_cursor_detected.total", "provider", "google", "source", source(sourceAttribution))
                    .increment();
            SyncBatch full = fetchFull(connection, sourceAttribution);
            return new SyncBatch(full.events(), full.nextCursor(), true, true, "stale_cursor_full_recovery");
        }
        try {
            GoogleApiClient.SyncWindow window = tokenRefresher.executeWithValidToken(
                    connection.getId(),
                    token -> googleApiClient.listEventsIncremental(token, cursor));
            if (shadowMode) {
                meterRegistry.counter("calendar.sync.incremental.shadow.total", "provider", "google", "source", source(sourceAttribution))
                        .increment();
                return new SyncBatch(List.of(), normalize(window.nextSyncToken()), false, false, "shadow_incremental");
            }
            return new SyncBatch(toIncoming(window.events()), normalize(window.nextSyncToken()), false, false, "incremental");
        } catch (CalendarClientException ex) {
            if (ex.getStatusCode() == 410) {
                meterRegistry.counter("calendar.sync.cursor_invalidated.total", "provider", "google", "source", source(sourceAttribution))
                        .increment();
                throw new SyncTokenInvalidException("Provider cursor invalidated: full resync required");
            }
            throw ex;
        }
    }

    @Override
    public SyncBatch fetchFull(CalendarConnection connection, SyncSourceAttribution sourceAttribution) {
        GoogleApiClient.SyncWindow window = tokenRefresher.executeWithValidToken(
                connection.getId(),
                googleApiClient::listEventsFull);
        meterRegistry.counter("calendar.sync.incremental_recovery.total", "provider", "google", "source", source(sourceAttribution))
                .increment();
        if (shadowMode) {
            return new SyncBatch(List.of(), normalize(window.nextSyncToken()), true, false, "shadow_full_recovery");
        }
        return new SyncBatch(toIncoming(window.events()), normalize(window.nextSyncToken()), true, false, "full_recovery");
    }

    private static List<CalendarEventIngestionService.IncomingCalendarEvent> toIncoming(
            List<GoogleApiClient.CalendarEventObservation> observations) {
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

    private static String source(SyncSourceAttribution sourceAttribution) {
        return sourceAttribution == null ? "UNKNOWN" : sourceAttribution.name();
    }

    private boolean isStale(Instant cursorUpdatedAt) {
        if (cursorUpdatedAt == null) {
            return true;
        }
        return cursorUpdatedAt.plus(staleCursorMaxAge).isBefore(Instant.now());
    }
}
