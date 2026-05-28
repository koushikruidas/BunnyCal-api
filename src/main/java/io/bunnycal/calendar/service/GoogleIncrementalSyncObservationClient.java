package io.bunnycal.calendar.service;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.GoogleApiClient;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionSyncCursor;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionSyncCursorRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google sync client.
 *
 * <p>Structurally mirrors {@link MicrosoftIncrementalSyncObservationClient}: iterate
 * the user's selected Google calendar ids, persist per-{@code (connection_id,
 * external_calendar_id)} {@code syncToken}s in {@link CalendarConnectionSyncCursor},
 * tag each {@link CalendarEventIngestionService.IncomingCalendarEvent} with its
 * source calendar id. The legacy single-cursor / "primary"-only behaviour is
 * gone — selecting non-primary Google calendars now actually polls them (audit
 * defect #3).
 *
 * <p>{@code calendar.sync.incremental.enabled} and
 * {@code calendar.sync.incremental.shadow-mode} are retained as injectable
 * properties so deployments still parsing them do not blow up, but the new
 * multi-calendar path does not consult them — they were defects (#4) in the old
 * single-cursor design and gating the new path on them would mean Google
 * multi-calendar silently continues to not work until someone flips a flag.
 */
@Component
public class GoogleIncrementalSyncObservationClient implements ExternalCalendarSyncClient {
    private static final Logger log = LoggerFactory.getLogger(GoogleIncrementalSyncObservationClient.class);

    /**
     * Stamped onto {@link CalendarConnection#getProviderSyncCursor()} after every
     * successful Google sweep. The connection-level scheduler reads this column to
     * decide "do we have a cursor or do we need a full resync"; real per-calendar
     * syncTokens live in {@link CalendarConnectionSyncCursor}.
     */
    static final String MULTI_CALENDAR_SENTINEL_CURSOR = "google_multi_calendar_v1";

    private final GoogleApiClient googleApiClient;
    private final TokenRefresher tokenRefresher;
    private final MeterRegistry meterRegistry;
    private final ProviderCalendarSelectionService selectionService;
    private final CalendarConnectionCalendarRepository inventoryRepository;
    private final CalendarConnectionSyncCursorRepository cursorRepository;

    public GoogleIncrementalSyncObservationClient(GoogleApiClient googleApiClient,
                                                  TokenRefresher tokenRefresher,
                                                  MeterRegistry meterRegistry,
                                                  ProviderCalendarSelectionService selectionService,
                                                  CalendarConnectionCalendarRepository inventoryRepository,
                                                  CalendarConnectionSyncCursorRepository cursorRepository) {
        this.googleApiClient = googleApiClient;
        this.tokenRefresher = tokenRefresher;
        this.meterRegistry = meterRegistry;
        this.selectionService = selectionService;
        this.inventoryRepository = inventoryRepository;
        this.cursorRepository = cursorRepository;
    }

    @Override
    public CalendarProviderType provider() {
        return CalendarProviderType.GOOGLE;
    }

    @Override
    public SyncBatch fetchIncremental(CalendarConnection connection, SyncSourceAttribution sourceAttribution)
            throws SyncTokenInvalidException {
        return syncSelectedCalendars(connection, sourceAttribution, false);
    }

    @Override
    public SyncBatch fetchFull(CalendarConnection connection, SyncSourceAttribution sourceAttribution) {
        return syncSelectedCalendars(connection, sourceAttribution, true);
    }

    private SyncBatch syncSelectedCalendars(CalendarConnection connection,
                                            SyncSourceAttribution sourceAttribution,
                                            boolean forceBootstrap) {
        Set<String> selected = resolveCalendarsToSync(connection, sourceAttribution);
        if (selected.isEmpty()) {
            log.info("google_calendar_sync_no_selection connectionId={} action=skip", connection.getId());
            return new SyncBatch(List.of(), MULTI_CALENDAR_SENTINEL_CURSOR, false, false, "no_selected_calendars");
        }

        List<CalendarEventIngestionService.IncomingCalendarEvent> merged = new ArrayList<>();
        boolean anySuccess = false;
        boolean tokenInvalidEncountered = false;

        for (String calendarId : selected) {
            try {
                syncOneCalendar(connection, calendarId, forceBootstrap, sourceAttribution, merged);
                anySuccess = true;
            } catch (CalendarClientException ex) {
                if (ex.getStatusCode() == 410) {
                    // Google's invalid-syncToken signal. Drop the persisted cursor for this
                    // calendar so the next sweep bootstraps it; do NOT abort the rest of the
                    // selected calendars — their cursors are still valid.
                    discardCursor(connection, calendarId, "google_sync_token_invalidated");
                    meterRegistry.counter("calendar.sync.cursor_invalidated.total",
                            "provider", "google",
                            "source", source(sourceAttribution)).increment();
                    log.warn("google_calendar_sync_token_invalidated connectionId={} calendarId={} action=cursor_dropped_for_next_bootstrap",
                            connection.getId(), calendarId);
                    tokenInvalidEncountered = true;
                } else {
                    // Any other Google error for one calendar is logged; the scheduler will
                    // retry it on the next sweep without aborting siblings.
                    meterRegistry.counter("google_calendar_sync_calendar_failure",
                            "provider", "google",
                            "calendarId", safeTag(calendarId)).increment();
                    log.warn("google_calendar_sync_failure connectionId={} calendarId={} status={} message={}",
                            connection.getId(), calendarId, ex.getStatusCode(), ex.getMessage());
                }
            }
        }

        // If at least one calendar's sync token was invalidated and at least one calendar
        // succeeded, surface the invalidation to the scheduler so its existing
        // SyncTokenInvalid handling runs. That gives operators the existing
        // "full resync recovery" signal even in the multi-calendar world.
        if (tokenInvalidEncountered && !anySuccess) {
            throw new SyncTokenInvalidException("All Google calendars rejected the persisted sync token");
        }

        String nextCursor = anySuccess ? MULTI_CALENDAR_SENTINEL_CURSOR : null;
        return new SyncBatch(
                merged,
                nextCursor,
                forceBootstrap,
                tokenInvalidEncountered,
                forceBootstrap ? "google_multi_calendar_full" : "google_multi_calendar_incremental");
    }

    /**
     * @return list of provider-native Google calendar ids to poll. Prefers the user's
     *         selected availability calendars; falls back to the connection's primary
     *         inventory calendar so a freshly-connected user with no event types still
     *         gets availability blocking for their primary calendar (same fallback the
     *         Microsoft client uses).
     */
    private Set<String> resolveCalendarsToSync(CalendarConnection connection,
                                               SyncSourceAttribution sourceAttribution) {
        Set<String> selected = selectionService.selectedAvailabilityCalendarIds(connection, sourceAttribution);
        List<CalendarConnectionCalendar> inventory =
                inventoryRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connection.getId());
        Set<String> inventoryIds = new HashSet<>();
        for (CalendarConnectionCalendar cal : inventory) {
            if (cal.getExternalCalendarId() != null && !cal.getExternalCalendarId().isBlank()) {
                inventoryIds.add(cal.getExternalCalendarId().trim());
            }
        }
        if (!selected.isEmpty()) {
            Set<String> normalizedSelected = new LinkedHashSet<>();
            for (String raw : selected) {
                String normalized = normalizeGoogleCalendarId(raw);
                if (normalized == null || normalized.isBlank()) continue;
                if (!inventoryIds.isEmpty() && !"primary".equalsIgnoreCase(normalized) && !inventoryIds.contains(normalized)) {
                    log.warn("google_calendar_inventory_mismatch connectionId={} selectedCalendarId={} availableInventoryIds={}",
                            connection.getId(), normalized, inventoryIds);
                    continue;
                }
                normalizedSelected.add(normalized);
            }
            if (!normalizedSelected.isEmpty()) {
                return normalizedSelected;
            }
            log.info("google_calendar_sync_selection_invalid connectionId={} action=fallback reason=all_selected_calendars_missing_from_inventory",
                    connection.getId());
        }

        Set<String> fallback = new LinkedHashSet<>();
        for (CalendarConnectionCalendar cal : inventory) {
            if (cal.isPrimary() && cal.getExternalCalendarId() != null && !cal.getExternalCalendarId().isBlank()) {
                fallback.add(cal.getExternalCalendarId());
                break;
            }
        }
        if (fallback.isEmpty() && !inventory.isEmpty()) {
            String first = inventory.get(0).getExternalCalendarId();
            if (first != null && !first.isBlank()) fallback.add(first);
        }
        if (fallback.isEmpty()) {
            // Inventory may not be hydrated yet (e.g. brand-new connection before the
            // OAuth callback's inventory hydration ran). Fall back to Google's reserved
            // "primary" alias so we don't silently no-op.
            fallback.add("primary");
            log.info("google_calendar_sync_fallback_to_primary_alias connectionId={} reason=no_inventory",
                    connection.getId());
        } else {
            log.info("google_calendar_sync_fallback_to_primary connectionId={} calendarId={} reason=no_event_type_selection",
                    connection.getId(), fallback.iterator().next());
        }
        return fallback;
    }

    private static String normalizeGoogleCalendarId(String value) {
        if (value == null || value.isBlank()) return null;
        String raw = value.trim();
        if (raw.startsWith("[") && raw.contains("](mailto:") && raw.endsWith(")")) {
            int start = raw.indexOf("](mailto:");
            String mailto = raw.substring(start + "](mailto:".length(), raw.length() - 1);
            if (!mailto.isBlank()) raw = mailto;
        } else if (raw.toLowerCase().startsWith("mailto:")) {
            raw = raw.substring("mailto:".length());
        }
        return raw.trim();
    }

    private void syncOneCalendar(CalendarConnection connection,
                                 String calendarId,
                                 boolean forceBootstrap,
                                 SyncSourceAttribution sourceAttribution,
                                 List<CalendarEventIngestionService.IncomingCalendarEvent> mergedOut) {
        if (ProviderCalendarSelectionService.isLegacyCorruption(connection.getId(), calendarId)) {
            log.warn("legacy_invalid_calendar_mapping connectionId={} calendarId={} reason=uuid_instead_of_provider_calendar_id stage=pre_google_call",
                    connection.getId(), calendarId);
            return;
        }
        CalendarConnectionSyncCursor cursorRow = cursorRepository
                .findByConnectionIdAndExternalCalendarId(connection.getId(), calendarId)
                .orElse(null);
        String syncToken = forceBootstrap ? null : (cursorRow == null ? null : cursorRow.getDeltaCursor());
        boolean hasCursor = syncToken != null && !syncToken.isBlank();

        log.info("google_calendar_sync_start connectionId={} calendarId={} hasCursor={}",
                connection.getId(), calendarId, hasCursor);

        Instant startedAt = Instant.now();
        GoogleApiClient.SyncWindow window;
        if (hasCursor) {
            String captured = syncToken;
            window = tokenRefresher.executeWithValidToken(
                    connection.getId(),
                    token -> googleApiClient.listEventsIncremental(token, calendarId, captured));
        } else {
            window = tokenRefresher.executeWithValidToken(
                    connection.getId(),
                    token -> googleApiClient.listEventsFull(token, calendarId));
        }
        long latencyMs = Math.max(0L, java.time.Duration.between(startedAt, Instant.now()).toMillis());
        meterRegistry.timer("google_calendar_sync_latency_ms",
                "provider", "google",
                "connectionId", connection.getId().toString(),
                "calendarId", safeTag(calendarId),
                "ingestionMode", hasCursor ? "incremental" : "bootstrap",
                "syncType", source(sourceAttribution))
                .record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        int rawCount = window.events() == null ? 0 : window.events().size();
        boolean hasNextSyncToken = window.nextSyncToken() != null && !window.nextSyncToken().isBlank();

        if (hasNextSyncToken) {
            persistSyncToken(connection, calendarId, window.nextSyncToken());
        }

        List<CalendarEventIngestionService.IncomingCalendarEvent> incoming = toIncoming(window.events(), calendarId);
        mergedOut.addAll(incoming);

        log.info("google_calendar_sync_complete connectionId={} calendarId={} eventCount={} syncTokenPersisted={}",
                connection.getId(), calendarId, rawCount, hasNextSyncToken);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistSyncToken(CalendarConnection connection, String calendarId, String syncToken) {
        CalendarConnectionSyncCursor row = cursorRepository
                .findByConnectionIdAndExternalCalendarId(connection.getId(), calendarId)
                .orElseGet(CalendarConnectionSyncCursor::new);
        row.setConnectionId(connection.getId());
        row.setExternalCalendarId(calendarId);
        row.setProvider(CalendarProviderType.GOOGLE);
        row.setDeltaCursor(syncToken);
        row.setLastSyncedAt(Instant.now());
        cursorRepository.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discardCursor(CalendarConnection connection, String calendarId, String reason) {
        cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), calendarId)
                .ifPresent(row -> {
                    row.setDeltaCursor(null);
                    cursorRepository.save(row);
                });
        log.info("google_calendar_sync_cursor_discarded connectionId={} calendarId={} reason={}",
                connection.getId(), calendarId, reason);
    }

    private static List<CalendarEventIngestionService.IncomingCalendarEvent> toIncoming(
            List<GoogleApiClient.CalendarEventObservation> observations,
            String calendarId) {
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }
        List<CalendarEventIngestionService.IncomingCalendarEvent> out = new ArrayList<>(observations.size());
        for (GoogleApiClient.CalendarEventObservation obs : observations) {
            out.add(new CalendarEventIngestionService.IncomingCalendarEvent(
                    obs.externalEventId(),
                    obs.startsAt() == null ? Instant.EPOCH : obs.startsAt(),
                    obs.endsAt() == null ? Instant.EPOCH.plusSeconds(60) : obs.endsAt(),
                    obs.cancelled(),
                    false,
                    obs.providerSequence(),
                    obs.providerUpdatedAt(),
                    obs.providerEtag(),
                    obs.payloadHash(),
                    calendarId,
                    obs.title(),
                    obs.location(),
                    obs.organizerEmail()));
        }
        return out;
    }

    private static String source(SyncSourceAttribution sourceAttribution) {
        return sourceAttribution == null ? "UNKNOWN" : sourceAttribution.name();
    }

    private static String safeTag(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
