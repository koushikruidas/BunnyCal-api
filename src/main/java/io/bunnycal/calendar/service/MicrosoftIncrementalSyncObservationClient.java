package io.bunnycal.calendar.service;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.MicrosoftApiClient;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MicrosoftIncrementalSyncObservationClient implements ExternalCalendarSyncClient {
    private static final Logger log = LoggerFactory.getLogger(MicrosoftIncrementalSyncObservationClient.class);
    static final int MAX_CURSOR_LENGTH = 4096;
    private static final Pattern REPEATED_PERCENT_ENCODING = Pattern.compile("%25(?:25){3,}", Pattern.CASE_INSENSITIVE);

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
    public CalendarProviderType provider() {
        return CalendarProviderType.MICROSOFT;
    }

    @Override
    public SyncBatch fetchIncremental(CalendarConnection connection, SyncSourceAttribution sourceAttribution) throws SyncTokenInvalidException {
        String cursor = normalize(connection.getProviderSyncCursor());
        if (cursor == null) {
            SyncBatch full = fetchFull(connection, sourceAttribution);
            return new SyncBatch(full.events(), full.nextCursor(), true, true, "missing_cursor_full_recovery");
        }
        if (isLikelyCorruptedCursor(cursor)) {
            log.warn("microsoft_incremental_cursor_invalid connectionId={} cursorLength={} reason=oversized_or_reencoded",
                    connection.getId(), cursor.length());
            meterRegistry.counter("calendar.sync.cursor_invalidated.total", "provider", "microsoft", "source", source(sourceAttribution))
                    .increment();
            throw new SyncTokenInvalidException("Microsoft incremental cursor is invalid; full resync required");
        }
        MicrosoftApiClient.SyncWindow window;
        try {
            window = tokenRefresher.executeWithValidToken(
                    connection.getId(),
                    token -> microsoftApiClient.listEventsIncremental(token, cursor));
        } catch (CalendarClientException ex) {
            if (isCursorCorruptionError(ex)) {
                log.warn("microsoft_incremental_cursor_rejected connectionId={} statusCode={} message={}",
                        connection.getId(), ex.getStatusCode(), ex.getMessage());
                meterRegistry.counter("calendar.sync.cursor_invalidated.total", "provider", "microsoft", "source", source(sourceAttribution))
                        .increment();
                throw new SyncTokenInvalidException("Microsoft incremental cursor rejected by provider; full resync required");
            }
            throw ex;
        }
        String nextCursor = normalize(window.nextDeltaCursor());
        if (nextCursor != null && isLikelyCorruptedCursor(nextCursor)) {
            log.warn("microsoft_incremental_next_cursor_invalid connectionId={} cursorLength={} reason=oversized_or_reencoded",
                    connection.getId(), nextCursor.length());
            meterRegistry.counter("calendar.sync.cursor_invalidated.total", "provider", "microsoft", "source", source(sourceAttribution))
                    .increment();
            throw new SyncTokenInvalidException("Microsoft next incremental cursor is invalid; full resync required");
        }
        meterRegistry.counter("calendar.sync.incremental.total", "provider", "microsoft").increment();
        return new SyncBatch(toIncoming(window.events()), nextCursor, false, false, "incremental");
    }

    @Override
    public SyncBatch fetchFull(CalendarConnection connection, SyncSourceAttribution sourceAttribution) {
        MicrosoftApiClient.SyncWindow window = tokenRefresher.executeWithValidToken(
                connection.getId(),
                microsoftApiClient::listEventsFull);
        meterRegistry.counter("calendar.sync.incremental_recovery.total",
                "provider", "microsoft",
                "source", source(sourceAttribution)).increment();
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

    static boolean isLikelyCorruptedCursor(String cursor) {
        if (cursor == null) {
            return false;
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            return true;
        }
        return REPEATED_PERCENT_ENCODING.matcher(cursor).find();
    }

    private static boolean isCursorCorruptionError(CalendarClientException ex) {
        if (ex.getStatusCode() != 400) {
            return false;
        }
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("request too long")
                || normalized.contains("size of the request headers is too long")
                || normalized.contains("header too long");
    }

    private static String source(SyncSourceAttribution sourceAttribution) {
        return sourceAttribution == null ? "UNKNOWN" : sourceAttribution.name();
    }
}
