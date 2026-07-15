package io.bunnycal.calendar.service;

import io.bunnycal.availability.engine.TimeInterval;
import io.bunnycal.availability.engine.IntervalUtils;
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
     * Returns busy intervals for the given day, clamped to it.
     *
     * <p>Which calendars contribute is a property of the <b>user</b>, not of the event being booked:
     * every calendar they left flagged {@code checks_availability}. There is no per-event-type
     * selection to pass in, which is deliberate — the old signature took a binding list whose
     * <em>empty</em> case meant "all connections block", so a caller that had no selection and a
     * caller whose selection resolved to nothing were indistinguishable, and one of them was wrong.
     */
    public List<TimeInterval> busyIntervalsForDate(
            UUID userId,
            LocalDate date,
            ZoneId zoneId) {
        List<BusyInterval> canonical = busyIntervalsForDateCanonical(userId, date, zoneId);
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

    /**
     * True when any busy event overlaps the half-open instant range {@code [start, end)}.
     *
     * <p>Unlike {@link #busyIntervalsForDate}, this does not clamp to a single local
     * day. A booking that crosses local midnight, or a busy event that starts the
     * evening before and runs into the slot, is caught — day-clamped intervals would
     * silently truncate both. Every local date the range touches is queried, so this
     * is also safe when callers in different timezones evaluate the same UTC instants.
     *
     */
    public boolean hasBusyConflict(
            UUID userId,
            Instant start,
            Instant end,
            ZoneId zoneId) {

        if (start == null || end == null || !start.isBefore(end)) {
            return false;
        }
        LocalDate firstDate = start.atZone(zoneId).toLocalDate();
        // end is exclusive: a slot ending exactly at local midnight belongs to the
        // day it started in, not the next one.
        LocalDate lastDate = end.minusNanos(1).atZone(zoneId).toLocalDate();

        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
            for (BusyInterval interval : busyIntervalsForDateCanonical(userId, date, zoneId)) {
                if (interval.start().isBefore(end) && interval.end().isAfter(start)) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<BusyInterval> busyIntervalsForDateCanonical(
            UUID userId,
            LocalDate date,
            ZoneId zoneId) {

        Instant dayStartUtc = date.atStartOfDay(zoneId).toInstant();
        Instant dayEndUtc   = date.plusDays(1).atStartOfDay(zoneId).toInstant();

        List<CalendarEvent> events =
                eventRepository.findBusyOnAvailabilityCalendars(userId, dayEndUtc, dayStartUtc);
        recordLegacyNullMetrics(events);

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
     * Counts events kept eligible only by the null-{@code external_calendar_id} wildcard — rows
     * ingested before per-calendar attribution existed, which belong to no inventory row and so
     * cannot be matched against the availability flag. They block unconditionally.
     *
     * <p>When this counter trends to zero from real traffic the wildcard can be retired and the
     * query made strict, at which point turning a calendar off would be honoured for every event on
     * it rather than only the attributed ones.
     */
    private void recordLegacyNullMetrics(List<CalendarEvent> events) {
        long legacyNullMatches = events.stream()
                .filter(e -> e.getExternalCalendarId() == null)
                .count();
        if (legacyNullMatches > 0) {
            meterRegistry.counter("calendar.busy_query.legacy_null_external_calendar_id.matches",
                    "scope", "availability_flag")
                    .increment(legacyNullMatches);
            log.info("calendar_busy_legacy_null_external_calendar_id matches={}", legacyNullMatches);
        }
    }
}
