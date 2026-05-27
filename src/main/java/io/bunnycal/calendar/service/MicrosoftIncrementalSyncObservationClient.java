package io.bunnycal.calendar.service;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.MicrosoftApiClient;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionSyncCursor;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionSyncCursorRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MicrosoftIncrementalSyncObservationClient implements ExternalCalendarSyncClient {
    private static final Logger log = LoggerFactory.getLogger(MicrosoftIncrementalSyncObservationClient.class);

    /**
     * Stamped onto {@code calendar_connections.provider_sync_cursor} after every
     * successful Microsoft sweep. The legacy scheduler reads this column to decide
     * "do we have a cursor or do we need a full resync"; real per-calendar deltaLinks
     * live in {@code calendar_connection_sync_cursors}.
     */
    static final String MULTI_CALENDAR_SENTINEL_CURSOR = "microsoft_multi_calendar_v1";

    private final MicrosoftApiClient microsoftApiClient;
    private final TokenRefresher tokenRefresher;
    private final MeterRegistry meterRegistry;
    private final ProviderCalendarSelectionService selectionService;
    private final CalendarConnectionCalendarRepository inventoryRepository;
    private final CalendarConnectionSyncCursorRepository cursorRepository;
    private final Duration bootstrapLookback;
    private final Duration bootstrapLookahead;

    public MicrosoftIncrementalSyncObservationClient(MicrosoftApiClient microsoftApiClient,
                                                     TokenRefresher tokenRefresher,
                                                     MeterRegistry meterRegistry,
                                                     ProviderCalendarSelectionService selectionService,
                                                     CalendarConnectionCalendarRepository inventoryRepository,
                                                     CalendarConnectionSyncCursorRepository cursorRepository,
                                                     @Value("${calendar.sync.microsoft.bootstrap-lookback-days:30}") int bootstrapLookbackDays,
                                                     @Value("${calendar.sync.microsoft.bootstrap-lookahead-days:90}") int bootstrapLookaheadDays) {
        this.microsoftApiClient = microsoftApiClient;
        this.tokenRefresher = tokenRefresher;
        this.meterRegistry = meterRegistry;
        this.selectionService = selectionService;
        this.inventoryRepository = inventoryRepository;
        this.cursorRepository = cursorRepository;
        this.bootstrapLookback = Duration.ofDays(Math.max(1, bootstrapLookbackDays));
        this.bootstrapLookahead = Duration.ofDays(Math.max(1, bootstrapLookaheadDays));
    }

    @Override
    public CalendarProviderType provider() {
        return CalendarProviderType.MICROSOFT;
    }

    @Override
    public SyncBatch fetchIncremental(CalendarConnection connection, SyncSourceAttribution sourceAttribution) {
        return syncSelectedCalendars(connection, sourceAttribution, false);
    }

    @Override
    public SyncBatch fetchFull(CalendarConnection connection, SyncSourceAttribution sourceAttribution) {
        // Full recovery = force every selected calendar to bootstrap from scratch by
        // discarding its persisted deltaLink. The legacy scheduler invokes this when
        // the connection-level cursor is invalidated; we honor that contract by
        // starting fresh for all selected calendars.
        return syncSelectedCalendars(connection, sourceAttribution, true);
    }

    private SyncBatch syncSelectedCalendars(CalendarConnection connection,
                                            SyncSourceAttribution sourceAttribution,
                                            boolean forceBootstrap) {
        Set<String> selected = resolveCalendarsToSync(connection, sourceAttribution);
        if (selected.isEmpty()) {
            log.info("microsoft_calendar_sync_no_selection connectionId={} action=skip", connection.getId());
            // Stamp the sentinel so the scheduler doesn't loop into full-resync mode.
            return new SyncBatch(List.of(), MULTI_CALENDAR_SENTINEL_CURSOR, false, false, "no_selected_calendars");
        }

        Instant windowStart = Instant.now().minus(bootstrapLookback);
        Instant windowEnd = Instant.now().plus(bootstrapLookahead);
        List<CalendarEventIngestionService.IncomingCalendarEvent> merged = new ArrayList<>();
        boolean fullResyncWindow = forceBootstrap;
        boolean anySuccess = false;

        for (String calendarId : selected) {
            try {
                int eventCount = syncOneCalendar(connection, calendarId, windowStart, windowEnd, forceBootstrap, sourceAttribution, merged);
                if (eventCount > 0 || true) {
                    // Any non-throwing iteration counts as success — the calendar was reachable.
                    anySuccess = true;
                }
            } catch (CalendarClientException ex) {
                // A single calendar's failure must not abort the rest. The scheduler will retry
                // the failing calendars on the next sweep; the others' deltaLinks already
                // advanced so they don't re-emit duplicate events next time.
                meterRegistry.counter("microsoft_calendar_sync_calendar_failure",
                        "provider", "microsoft",
                        "calendarId", safeTag(calendarId)).increment();
                log.warn("microsoft_calendar_sync_failure connectionId={} calendarId={} status={} message={}",
                        connection.getId(), calendarId, ex.getStatusCode(), ex.getMessage());
            }
        }

        // Determine whether the connection-level cursor needs a sentinel stamp. The
        // scheduler interprets `null` as "no cursor — do full recovery"; stamping the
        // sentinel after the first successful pass takes Microsoft out of that loop.
        String nextCursor = anySuccess ? MULTI_CALENDAR_SENTINEL_CURSOR : null;
        return new SyncBatch(merged, nextCursor, fullResyncWindow, false,
                forceBootstrap ? "microsoft_multi_calendar_full" : "microsoft_multi_calendar_incremental");
    }

    /**
     * @return list of provider-native calendar ids to poll. Prefers the user's selected
     *         availability calendars; falls back to the connection's primary inventory
     *         calendar when no event types reference any (otherwise sync would silently
     *         stop producing busy intervals after a fresh connect).
     */
    private Set<String> resolveCalendarsToSync(CalendarConnection connection,
                                               SyncSourceAttribution sourceAttribution) {
        Set<String> selected = selectionService.selectedAvailabilityCalendarIds(connection, sourceAttribution);
        if (!selected.isEmpty()) {
            return selected;
        }
        // Fallback: poll the inventory's primary calendar (deterministically the first
        // entry returned by findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc).
        List<CalendarConnectionCalendar> inventory =
                inventoryRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connection.getId());
        Set<String> fallback = new LinkedHashSet<>();
        for (CalendarConnectionCalendar cal : inventory) {
            if (cal.isPrimary() && cal.getExternalCalendarId() != null && !cal.getExternalCalendarId().isBlank()) {
                fallback.add(cal.getExternalCalendarId());
                break;
            }
        }
        if (fallback.isEmpty() && !inventory.isEmpty()) {
            // No primary marked: use the first available calendar so we still produce
            // busy intervals. Better than a silent no-op sync.
            String first = inventory.get(0).getExternalCalendarId();
            if (first != null && !first.isBlank()) fallback.add(first);
        }
        if (!fallback.isEmpty()) {
            log.info("microsoft_calendar_sync_fallback_to_primary connectionId={} calendarId={} reason=no_event_type_selection",
                    connection.getId(), fallback.iterator().next());
        }
        return fallback;
    }

    private int syncOneCalendar(CalendarConnection connection,
                                String calendarId,
                                Instant windowStart,
                                Instant windowEnd,
                                boolean forceBootstrap,
                                SyncSourceAttribution sourceAttribution,
                                List<CalendarEventIngestionService.IncomingCalendarEvent> mergedOut) {
        if (ProviderCalendarSelectionService.isLegacyCorruption(connection.getId(), calendarId)) {
            log.warn("legacy_invalid_calendar_mapping connectionId={} calendarId={} reason=uuid_instead_of_provider_calendar_id stage=pre_graph",
                    connection.getId(), calendarId);
            return 0;
        }
        CalendarConnectionSyncCursor cursorRow = cursorRepository
                .findByConnectionIdAndExternalCalendarId(connection.getId(), calendarId)
                .orElse(null);
        String deltaCursor = forceBootstrap ? null : (cursorRow == null ? null : cursorRow.getDeltaCursor());
        boolean hasCursor = deltaCursor != null && !deltaCursor.isBlank();

        log.info("microsoft_calendar_sync_start connectionId={} calendarId={} hasCursor={}",
                connection.getId(), calendarId, hasCursor);

        Instant startedAt = Instant.now();
        MicrosoftApiClient.SyncWindow window = tokenRefresher.executeWithValidToken(
                connection.getId(),
                token -> microsoftApiClient.listCalendarViewDelta(token, calendarId, windowStart, windowEnd, deltaCursor));
        long latencyMs = Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis());
        meterRegistry.timer("microsoft_getschedule_latency_ms",
                "provider", "microsoft",
                "connectionId", connection.getId().toString(),
                "calendarId", safeTag(calendarId),
                "tenantId", "unknown",
                "ingestionMode", hasCursor ? "incremental" : "bootstrap",
                "syncType", source(sourceAttribution))
                .record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        int rawCount = window.events() == null ? 0 : window.events().size();
        boolean hasDeltaCursor = window.nextDeltaCursor() != null && !window.nextDeltaCursor().isBlank();

        // Persist ONLY the deltaLink, never a nextLink. The HTTP client already
        // unwraps pagination and returns null in nextDeltaCursor when the terminal
        // page didn't surface a deltaLink — in that pathological case we keep the
        // previous cursor so the next sweep retries cleanly.
        if (hasDeltaCursor) {
            persistDeltaCursor(connection, calendarId, window.nextDeltaCursor());
        }

        List<CalendarEventIngestionService.IncomingCalendarEvent> incoming = toIncoming(window.events(), calendarId);
        mergedOut.addAll(incoming);

        log.info("microsoft_calendar_delta_complete connectionId={} calendarId={} eventCount={} deltaCursorPersisted={}",
                connection.getId(), calendarId, rawCount, hasDeltaCursor);
        return rawCount;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistDeltaCursor(CalendarConnection connection, String calendarId, String deltaCursor) {
        CalendarConnectionSyncCursor row = cursorRepository
                .findByConnectionIdAndExternalCalendarId(connection.getId(), calendarId)
                .orElseGet(CalendarConnectionSyncCursor::new);
        row.setConnectionId(connection.getId());
        row.setExternalCalendarId(calendarId);
        row.setProvider(CalendarProviderType.MICROSOFT);
        row.setDeltaCursor(deltaCursor);
        row.setLastSyncedAt(Instant.now());
        cursorRepository.save(row);
    }

    private List<CalendarEventIngestionService.IncomingCalendarEvent> toIncoming(
            List<MicrosoftApiClient.CalendarEventObservation> observations,
            String calendarId) {
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }
        List<CalendarEventIngestionService.IncomingCalendarEvent> out = new ArrayList<>(observations.size());
        for (MicrosoftApiClient.CalendarEventObservation obs : observations) {
            out.add(new CalendarEventIngestionService.IncomingCalendarEvent(
                    obs.externalEventId(),
                    obs.startsAt() == null ? Instant.EPOCH : obs.startsAt(),
                    obs.endsAt() == null ? Instant.EPOCH.plusSeconds(60) : obs.endsAt(),
                    obs.cancelled(),
                    obs.providerSequence(),
                    obs.providerUpdatedAt(),
                    obs.providerEtag(),
                    obs.payloadHash(),
                    calendarId));
        }
        return out;
    }

    private static String source(SyncSourceAttribution sourceAttribution) {
        return sourceAttribution == null ? "UNKNOWN" : sourceAttribution.name();
    }

    private static String safeTag(String value) {
        if (value == null || value.isBlank()) return "unknown";
        // Micrometer tag values: keep short to avoid blowing up cardinality.
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
