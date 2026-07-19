package io.bunnycal.session.service;

import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.service.CalendarBusyTimeService;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.repository.EventSessionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Answers "can the host actually be here?" for a proposed group-session time.
 *
 * <p>Used from two places with deliberately different weight: the preview endpoint calls it to
 * drive the reschedule dialog, and {@code SessionService.rescheduleSession} calls it again inside
 * the write transaction. The preview is advisory and inherently racy — a conflict can appear
 * between the host seeing a clear dialog and clicking confirm — so the transactional call is the
 * authority and must never be skipped on the strength of a clean preview.
 */
@Service
public class RescheduleConflictService {

    private final EventSessionRepository sessionRepository;
    private final BookingRepository bookingRepository;
    private final CalendarBusyTimeService calendarBusyTimeService;

    public RescheduleConflictService(EventSessionRepository sessionRepository,
                                     BookingRepository bookingRepository,
                                     CalendarBusyTimeService calendarBusyTimeService) {
        this.sessionRepository = sessionRepository;
        this.bookingRepository = bookingRepository;
        this.calendarBusyTimeService = calendarBusyTimeService;
    }

    /**
     * @param excludeSessionId the session being moved, so it never conflicts with itself
     */
    public RescheduleConflicts check(UUID hostId, Instant start, Instant end, UUID excludeSessionId) {
        if (hostId == null || start == null || end == null || !start.isBefore(end)) {
            return RescheduleConflicts.none();
        }

        List<RescheduleConflicts.Conflict> hard = new ArrayList<>();

        for (EventSession s : sessionRepository.findOverlappingSessions(hostId, start, end, excludeSessionId)) {
            hard.add(new RescheduleConflicts.Conflict(
                    "Group session", s.getStartTime(), s.getEndTime(), "GROUP_SESSION"));
        }

        // Covers 1:1, round-robin and collective in one predicate — see
        // BookingRepository.findOverlappingForUser for why host_id alone is not enough.
        bookingRepository.findOverlappingForUser(hostId, start, end).forEach(b ->
                hard.add(new RescheduleConflicts.Conflict(
                        b.getEventTypeName() != null ? b.getEventTypeName() : "Booking",
                        b.getStartTime(), b.getEndTime(), "BOOKING")));

        List<RescheduleConflicts.Conflict> soft = new ArrayList<>();
        for (CalendarEvent e : calendarBusyTimeService.busyEvents(hostId, start, end)) {
            soft.add(new RescheduleConflicts.Conflict(
                    e.getTitle() != null && !e.getTitle().isBlank() ? e.getTitle() : "Busy",
                    e.getStartsAt(), e.getEndsAt(), "EXTERNAL_CALENDAR"));
        }

        return new RescheduleConflicts(List.copyOf(hard), List.copyOf(soft));
    }
}
