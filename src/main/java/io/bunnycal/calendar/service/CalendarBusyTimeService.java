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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CalendarBusyTimeService {

    private static final Logger log = LoggerFactory.getLogger(CalendarBusyTimeService.class);

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
     * When {@code availabilityBindings} is non-empty only events belonging to the
     * explicitly selected connections are included.  When it is empty (no explicit
     * selection) ALL active connections contribute — preserving the original
     * "use everything" behaviour for event types that were created before per-type
     * selection was introduced.
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
            // Explicit calendar selection: only query the nominated connections.
            Set<UUID> connectionIds = availabilityBindings.stream()
                    .map(AvailabilityBinding::connectionId)
                    .collect(Collectors.toSet());
            log.debug("availability[userId={} date={}] explicit connections={}", userId, date, connectionIds);
            events = eventRepository
                    .findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                            connectionIds, dayEndUtc, dayStartUtc);
        } else {
            // No explicit selection: fall back to all active connections (backward-compatible).
            if (connectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE).isEmpty()) {
                log.debug("availability[userId={} date={}] no active connections — returning empty", userId, date);
                return List.of();
            }
            log.debug("availability[userId={} date={}] no explicit selection — using all active connections", userId, date);
            events = eventRepository
                    .findByUserIdAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
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
}
