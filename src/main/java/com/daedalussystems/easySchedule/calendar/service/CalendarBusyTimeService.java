package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.availability.engine.TimeInterval;
import com.daedalussystems.easySchedule.availability.engine.IntervalUtils;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarEvent;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.calendar.repository.CalendarEventRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CalendarBusyTimeService {
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventRepository eventRepository;

    public CalendarBusyTimeService(CalendarConnectionRepository connectionRepository,
                                   CalendarEventRepository eventRepository) {
        this.connectionRepository = connectionRepository;
        this.eventRepository = eventRepository;
    }

    public List<TimeInterval> busyIntervalsForDate(UUID userId, LocalDate date, ZoneId zoneId) {
        // Source-of-truth: normalized calendar_events only.
        if (connectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE).isEmpty()) {
            return List.of();
        }

        Instant dayStartUtc = date.atStartOfDay(zoneId).toInstant();
        Instant dayEndUtc = date.plusDays(1).atStartOfDay(zoneId).toInstant();

        List<CalendarEvent> events = eventRepository
                .findByUserIdAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(userId, dayEndUtc, dayStartUtc);

        List<TimeInterval> intervals = new ArrayList<>(events.size());
        ZonedDateTime dayStart = date.atStartOfDay(zoneId);
        ZonedDateTime dayEnd = dayStart.plusDays(1);
        for (CalendarEvent event : events) {
            ZonedDateTime start = event.getStartsAt().atZone(zoneId);
            ZonedDateTime end = event.getEndsAt().atZone(zoneId);
            if (start.isBefore(dayStart)) {
                start = dayStart;
            }
            if (end.isAfter(dayEnd)) {
                end = dayEnd;
            }
            if (start.isBefore(end)) {
                intervals.add(new TimeInterval(start, end));
            }
        }

        return IntervalUtils.normalize(intervals);
    }
}
