package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.availability.engine.TimeInterval;
import com.daedalussystems.easySchedule.availability.engine.IntervalUtils;
import com.daedalussystems.easySchedule.availability.service.EventTypeOrchestrationNormalizer.AvailabilityBinding;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarEvent;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.calendar.repository.CalendarEventRepository;
import com.daedalussystems.easySchedule.common.time.DateTimeUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CalendarBusyTimeService {

    private static final Logger log = LoggerFactory.getLogger(CalendarBusyTimeService.class);

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventRepository eventRepository;

    public CalendarBusyTimeService(CalendarConnectionRepository connectionRepository,
                                   CalendarEventRepository eventRepository) {
        this.connectionRepository = connectionRepository;
        this.eventRepository = eventRepository;
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

        List<TimeInterval> intervals = new ArrayList<>(events.size());
        ZonedDateTime dayStart = date.atStartOfDay(zoneId);
        ZonedDateTime dayEnd   = dayStart.plusDays(1);
        for (CalendarEvent event : events) {
            ZonedDateTime start = DateTimeUtils.toZone(event.getStartsAt(), zoneId);
            ZonedDateTime end   = DateTimeUtils.toZone(event.getEndsAt(),   zoneId);
            if (start.isBefore(dayStart)) start = dayStart;
            if (end.isAfter(dayEnd))      end   = dayEnd;
            if (start.isBefore(end)) {
                intervals.add(new TimeInterval(start, end));
            }
        }

        List<TimeInterval> merged = IntervalUtils.normalize(intervals);
        log.debug("availability[userId={} date={}] events={} merged-intervals={}",
                userId, date, events.size(), merged.size());
        return merged;
    }
}
