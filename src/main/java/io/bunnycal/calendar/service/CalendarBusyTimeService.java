package io.bunnycal.calendar.service;

import io.bunnycal.availability.engine.TimeInterval;
import io.bunnycal.availability.engine.IntervalUtils;
import io.bunnycal.availability.service.EventTypeOrchestrationNormalizer.AvailabilityBinding;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarEventRepository;
import io.bunnycal.common.time.DateTimeUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CalendarBusyTimeService {

    private static final Logger log = LoggerFactory.getLogger(CalendarBusyTimeService.class);

    // Sentinel values for the calendar-scoped query. JPQL `IN ()` against an empty
    // collection is invalid in Hibernate, so when a partition bucket is empty we
    // pass a single deliberately-impossible value that cannot match any real row.
    private static final UUID NO_CONNECTION_SENTINEL = new UUID(0L, 0L);
    private static final String NO_CALENDAR_SENTINEL =
            "__bunnycal_no_calendar_sentinel__";

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventRepository eventRepository;
    private final MeterRegistry meterRegistry;

    public CalendarBusyTimeService(CalendarConnectionRepository connectionRepository,
                                   CalendarEventRepository eventRepository,
                                   MeterRegistry meterRegistry) {
        this.connectionRepository = connectionRepository;
        this.eventRepository = eventRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Returns busy intervals for the given day.
     *
     * <p>Selection semantics:
     *
     * <ul>
     *   <li>When {@code availabilityBindings} is null or empty: no explicit selection.
     *       Every active connection's events contribute (legacy {@code ALL_CONNECTED}
     *       behaviour for event types created before per-type selection existed).</li>
     *   <li>When at least one binding has a null {@code externalCalendarId}: that
     *       binding selects the whole connection (legacy connection-level selection
     *       shape). Mixing with calendar-scoped bindings is supported — the whole
     *       connection wins for its own connectionId.</li>
     *   <li>When every binding for a connection carries a real
     *       {@code externalCalendarId}: only events stamped with one of those calendar
     *       ids contribute, plus events whose {@code external_calendar_id} is NULL
     *       (legacy-compatibility wildcard documented on {@link CalendarEventRepository#findBusySelected}).</li>
     * </ul>
     */
    public List<TimeInterval> busyIntervalsForDate(
            UUID userId,
            LocalDate date,
            ZoneId zoneId,
            List<AvailabilityBinding> availabilityBindings) {
        List<BusyInterval> canonical = busyIntervalsForDateCanonical(userId, date, zoneId, availabilityBindings);
        List<TimeInterval> intervals = new ArrayList<>(canonical.size());
        ZonedDateTime dayStart = date.atStartOfDay(zoneId);
        ZonedDateTime dayEnd = dayStart.plusDays(1);
        for (BusyInterval interval : canonical) {
            ZonedDateTime start = DateTimeUtils.toZone(interval.start(), zoneId);
            ZonedDateTime end = DateTimeUtils.toZone(interval.end(), zoneId);
            if (start.isBefore(dayStart)) start = dayStart;
            if (end.isAfter(dayEnd)) end = dayEnd;
            if (start.isBefore(end)) {
                intervals.add(new TimeInterval(start, end));
            }
        }
        return IntervalUtils.normalize(intervals);
    }

    public List<BusyInterval> busyIntervalsForDateCanonical(
            UUID userId,
            LocalDate date,
            ZoneId zoneId,
            List<AvailabilityBinding> availabilityBindings) {

        Instant dayStartUtc = date.atStartOfDay(zoneId).toInstant();
        Instant dayEndUtc   = date.plusDays(1).atStartOfDay(zoneId).toInstant();

        List<CalendarEvent> events;

        if (availabilityBindings != null && !availabilityBindings.isEmpty()) {
            BindingPartition partition = partition(availabilityBindings);
            if (partition.isEmpty()) {
                // Every binding was malformed (null connection id). Treat as
                // "no events block" rather than silently falling back to all-connections —
                // explicit selection that resolves to nothing should not behave like
                // no-selection-at-all.
                log.warn("availability[userId={} date={}] all bindings malformed (null connectionId) — returning empty",
                        userId, date);
                return List.of();
            }
            log.debug("availability[userId={} date={}] wholeConnections={} scopedConnections={} selectedCalendars={}",
                    userId, date,
                    partition.wholeConnectionIds, partition.calendarScopedConnectionIds, partition.selectedExternalCalendarIds);
            events = eventRepository.findBusySelected(
                    nonEmptyOrSentinel(partition.wholeConnectionIds, NO_CONNECTION_SENTINEL),
                    nonEmptyOrSentinel(partition.calendarScopedConnectionIds, NO_CONNECTION_SENTINEL),
                    nonEmptyOrSentinel(partition.selectedExternalCalendarIds, NO_CALENDAR_SENTINEL),
                    dayEndUtc,
                    dayStartUtc);
            recordLegacyNullMetrics(events, partition);
        } else {
            // No explicit selection: fall back to all active connections (backward-compatible).
            if (connectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE).isEmpty()) {
                log.debug("availability[userId={} date={}] no active connections — returning empty", userId, date);
                return List.of();
            }
            log.debug("availability[userId={} date={}] no explicit selection — using all active connections", userId, date);
            events = eventRepository
                    .findByUserIdAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                            userId, dayEndUtc, dayStartUtc);
        }

        List<BusyInterval> intervals = new ArrayList<>(events.size());
        for (CalendarEvent event : events) {
            if (event.getStartsAt() != null && event.getEndsAt() != null && event.getStartsAt().isBefore(event.getEndsAt())) {
                intervals.add(new BusyInterval(
                        event.getStartsAt(),
                        event.getEndsAt(),
                        event.getProvider(),
                        event.getConnectionId() == null ? "unknown" : event.getConnectionId().toString(),
                        event.getExternalEventId(),
                        "calendar_event_projection",
                        event.getUpdatedAt() == null ? Instant.now() : event.getUpdatedAt()));
            }
        }
        intervals.sort(Comparator.comparing(BusyInterval::start));
        long microsoftCount = intervals.stream()
                .filter(i -> "MICROSOFT".equalsIgnoreCase(i.sourceProvider()))
                .count();
        log.info("availability_busy_intervals_canonicalized userId={} date={} total={} microsoft={} google={}",
                userId,
                date,
                intervals.size(),
                microsoftCount,
                intervals.stream().filter(i -> "GOOGLE".equalsIgnoreCase(i.sourceProvider())).count());
        if (microsoftCount > 0) {
            Instant latestMicrosoftEnd = intervals.stream()
                    .filter(i -> "MICROSOFT".equalsIgnoreCase(i.sourceProvider()))
                    .map(BusyInterval::end)
                    .max(Instant::compareTo)
                    .orElse(dayStartUtc);
            log.info("microsoft_availability_ingestion_freshness userId={} date={} latestBusyEndUtc={} windowEndUtc={}",
                    userId, date, latestMicrosoftEnd, dayEndUtc);
            double ageSeconds = Math.max(0d, (double) java.time.Duration.between(latestMicrosoftEnd, Instant.now()).toSeconds());
            meterRegistry.gauge("microsoft_availability_ingestion_age_seconds",
                    java.util.List.of(
                            Tag.of("provider", "microsoft"),
                            Tag.of("connectionId", "mixed"),
                            Tag.of("calendarId", "unknown"),
                            Tag.of("tenantId", "unknown"),
                            Tag.of("ingestionMode", "canonical_projection"),
                            Tag.of("syncType", "pull_or_webhook")),
                    ageSeconds);
            meterRegistry.gauge("microsoft_busy_interval_count",
                    java.util.List.of(
                            Tag.of("provider", "microsoft"),
                            Tag.of("connectionId", "mixed"),
                            Tag.of("calendarId", "unknown"),
                            Tag.of("tenantId", "unknown"),
                            Tag.of("ingestionMode", "canonical_projection"),
                            Tag.of("syncType", "pull_or_webhook")),
                    microsoftCount);
            if (latestMicrosoftEnd.isBefore(Instant.now().minusSeconds(3600))) {
                meterRegistry.counter("microsoft_availability_stale_state_total",
                        "provider", "microsoft",
                        "connectionId", "mixed",
                        "calendarId", "unknown",
                        "tenantId", "unknown",
                        "ingestionMode", "canonical_projection",
                        "syncType", "pull_or_webhook").increment();
                log.warn("microsoft_availability_stale_window_detected userId={} date={} latestBusyEndUtc={} nowUtc={}",
                        userId, date, latestMicrosoftEnd, Instant.now());
            }
        }
        return List.copyOf(intervals);
    }

    /**
     * Partition the binding list into:
     *
     * <ul>
     *   <li>Whole-connection ids — bindings with null externalCalendarId. The entire
     *       connection contributes events.</li>
     *   <li>Calendar-scoped connection ids + selected external calendar ids — bindings
     *       with a real externalCalendarId.</li>
     * </ul>
     *
     * <p>If the same connection appears in both buckets the whole-connection entry
     * subsumes the scoped entries — we remove it from the scoped bucket so the
     * connection contributes via the wholeConnectionIds branch only.
     */
    static BindingPartition partition(List<AvailabilityBinding> bindings) {
        Set<UUID> whole = new LinkedHashSet<>();
        Set<UUID> scoped = new LinkedHashSet<>();
        Set<String> calendars = new LinkedHashSet<>();
        for (AvailabilityBinding b : bindings) {
            if (b == null || b.connectionId() == null) continue;
            String calendarId = b.externalCalendarId();
            if (calendarId == null || calendarId.isBlank()) {
                whole.add(b.connectionId());
            } else {
                scoped.add(b.connectionId());
                calendars.add(calendarId);
            }
        }
        scoped.removeAll(whole);
        return new BindingPartition(whole, scoped, calendars);
    }

    private static <T> Collection<T> nonEmptyOrSentinel(Set<T> values, T sentinel) {
        return values.isEmpty() ? List.of(sentinel) : values;
    }

    /**
     * Telemetry hook for the legacy null-row decay curve (audit fix #1, telemetry
     * variant). Emits a counter for every event row matched by the calendar-scoped
     * branch whose {@code external_calendar_id} is null — these are rows ingested
     * before per-calendar attribution existed and are being kept eligible only by
     * the compatibility wildcard. When this counter trends to zero from real
     * traffic, the wildcard rule can be retired and the query made strict.
     */
    private void recordLegacyNullMetrics(List<CalendarEvent> events, BindingPartition partition) {
        if (partition.calendarScopedConnectionIds.isEmpty()) return;
        long legacyNullMatches = events.stream()
                .filter(e -> partition.calendarScopedConnectionIds.contains(e.getConnectionId()))
                .filter(e -> e.getExternalCalendarId() == null)
                .count();
        if (legacyNullMatches > 0) {
            meterRegistry.counter("calendar.busy_query.legacy_null_external_calendar_id.matches",
                    "scope", "calendar_scoped_bucket")
                    .increment(legacyNullMatches);
            log.info("calendar_busy_legacy_null_external_calendar_id matches={} scopedConnections={}",
                    legacyNullMatches, partition.calendarScopedConnectionIds.size());
        }
    }

    record BindingPartition(Set<UUID> wholeConnectionIds,
                            Set<UUID> calendarScopedConnectionIds,
                            Set<String> selectedExternalCalendarIds) {
        boolean isEmpty() {
            return wholeConnectionIds.isEmpty() && calendarScopedConnectionIds.isEmpty();
        }
    }
}
