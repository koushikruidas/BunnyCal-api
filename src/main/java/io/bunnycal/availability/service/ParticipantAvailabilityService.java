package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.AvailabilityOverride;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.domain.EventAvailabilityWindow;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.engine.RecurrenceWindowFilter;
import io.bunnycal.availability.engine.SlotGenerationEngine;
import io.bunnycal.availability.engine.SlotGenerationEngine.BookingWindow;
import io.bunnycal.availability.engine.SlotGenerationEngine.SlotInput;
import io.bunnycal.availability.engine.SlotGenerationEngine.SlotUtc;
import io.bunnycal.availability.engine.TimeInterval;
import io.bunnycal.availability.repository.AvailabilityOverrideRepository;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.EventAvailabilityWindowRepository;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.calendar.service.BusyInterval;
import io.bunnycal.calendar.service.CalendarBusyTimeService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.DateTimeUtils;
import io.bunnycal.common.time.TimeConversionService;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.repository.EventSessionRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Computes per-participant slot candidates for ROUND_ROBIN aggregation.
 *
 * <p>Each participant's availability is computed independently using their OWN rules,
 * overrides, bookings, sessions, and calendar busy blocks — never the event type owner's.
 * This mirrors the logic in {@link SlotService#compute} but scoped to a given participant.
 *
 * <p>MUST NOT be used for ONE_ON_ONE or GROUP paths.
 */
@Service
public class ParticipantAvailabilityService {

    private final UserRepository userRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final AvailabilityOverrideRepository availabilityOverrideRepository;
    private final BookingRepository bookingRepository;
    private final EventSessionRepository eventSessionRepository;
    private final GroupEventReservationWindowRepository reservationWindowRepository;
    private final EventAvailabilityWindowRepository eventAvailabilityWindowRepository;
    private final CalendarBusyTimeService calendarBusyTimeService;
    private final EventTypeOrchestrationJsonCodec orchestrationJsonCodec;
    private final TimeConversionService timeConversionService;

    public ParticipantAvailabilityService(
            UserRepository userRepository,
            AvailabilityRuleRepository availabilityRuleRepository,
            AvailabilityOverrideRepository availabilityOverrideRepository,
            BookingRepository bookingRepository,
            EventSessionRepository eventSessionRepository,
            GroupEventReservationWindowRepository reservationWindowRepository,
            EventAvailabilityWindowRepository eventAvailabilityWindowRepository,
            CalendarBusyTimeService calendarBusyTimeService,
            EventTypeOrchestrationJsonCodec orchestrationJsonCodec,
            TimeConversionService timeConversionService) {
        this.userRepository = userRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.availabilityOverrideRepository = availabilityOverrideRepository;
        this.bookingRepository = bookingRepository;
        this.eventSessionRepository = eventSessionRepository;
        this.reservationWindowRepository = reservationWindowRepository;
        this.eventAvailabilityWindowRepository = eventAvailabilityWindowRepository;
        this.calendarBusyTimeService = calendarBusyTimeService;
        this.orchestrationJsonCodec = orchestrationJsonCodec;
        this.timeConversionService = timeConversionService;
    }

    /**
     * Computes the slot candidates contributed by {@code participantUserId} for the
     * given event type and date.
     *
     * <p>All data is loaded scoped to the participant — rules, override, bookings,
     * sessions, calendar busy — never the event type owner's data.
     *
     * @param participantUserId the participant whose availability is being computed
     * @param eventType the event type being queried (used for duration/interval/constraints)
     * @param date the date for which slots are generated
     * @param now the "current" timestamp (from DB clock, passed in to avoid redundant reads)
     * @return participant's available slot candidates as UTC instants
     */
    public List<SlotUtc> computeForParticipant(
            UUID participantUserId,
            EventType eventType,
            LocalDate date,
            Instant now) {

        // 1. Load participant (need timezone).
        User participant = userRepository.findById(participantUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Participant user not found: " + participantUserId));

        // 2. Resolve participant's own timezone.
        ZoneId zoneId = timeConversionService.resolveZone(participant.getTimezone());

        // 3. Load participant's own availability rules.
        List<AvailabilityRule> rules =
                availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(participantUserId);

        // 4. Load participant's own override (nullable).
        AvailabilityOverride override =
                availabilityOverrideRepository.findByUserIdAndDate(participantUserId, date).orElse(null);

        // 5. Compute day boundaries in participant's timezone.
        Instant dayStartUtc = timeConversionService.dayStartUtc(date, participant.getTimezone());
        Instant dayEndUtc = timeConversionService.dayEndUtcExclusive(date, participant.getTimezone());

        // 6. Load participant's own bookings (ROUND_ROBIN is demand-driven, not GROUP).
        List<Booking> dayBookings =
                bookingRepository.findActiveOverlappingBookings(participantUserId, dayStartUtc, dayEndUtc);
        List<BookingWindow> bookingWindows = new ArrayList<>(dayBookings.size());
        for (Booking booking : dayBookings) {
            bookingWindows.add(new BookingWindow(booking.getStartTime(), booking.getEndTime()));
        }

        // 7. Load participant's own session blockers.
        List<EventSession> blockingSessions = eventSessionRepository
                .findAvailabilityBlockingSessionsInRange(participantUserId, eventType.getId(), dayStartUtc, dayEndUtc);
        List<BookingWindow> sessionBlockerWindows = new ArrayList<>(blockingSessions.size());
        for (EventSession session : blockingSessions) {
            sessionBlockerWindows.add(new BookingWindow(session.getStartTime(), session.getEndTime()));
        }

        // 8. Reservation window blockers for participant's OTHER event types.
        //    Windows owned by OTHER event types of this participant block the queried
        //    event type's slots via the session-blocker list.
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        List<GroupEventReservationWindow> reservationWindows =
                reservationWindowRepository.findBlockingCandidatesForDate(
                        participantUserId, eventType.getId(), date, dayOfWeek.name());
        for (GroupEventReservationWindow window : reservationWindows) {
            if (!RecurrenceWindowFilter.appliesOn(window, date)) {
                continue;
            }
            if (window.getStartTime() == null
                    || window.getEndTime() == null
                    || !window.getStartTime().isBefore(window.getEndTime())) {
                continue;
            }
            Instant start = timeConversionService.toUtcInstant(date, window.getStartTime(), participant.getTimezone());
            Instant end = timeConversionService.toUtcInstant(date, window.getEndTime(), participant.getTimezone());
            sessionBlockerWindows.add(new BookingWindow(start, end));
        }

        // 9. Event availability filter — demand-driven (ROUND_ROBIN is like ONE_ON_ONE
        //    here): own event's recurring filter windows narrow availability. Empty = full.
        List<EventAvailabilityWindow> filterWindows =
                eventAvailabilityWindowRepository.findOwnWindowsForDay(eventType.getId(), dayOfWeek.name());
        List<BookingWindow> eventAvailabilityFilter = new ArrayList<>(filterWindows.size());
        for (EventAvailabilityWindow window : filterWindows) {
            if (window.getStartTime() == null
                    || window.getEndTime() == null
                    || !window.getStartTime().isBefore(window.getEndTime())) {
                continue;
            }
            // Filter windows are stored in participant's timezone (same as host's timezone
            // convention for the event type, but we compute in participant's timezone here).
            Instant start = timeConversionService.toUtcInstant(date, window.getStartTime(), participant.getTimezone());
            Instant end = timeConversionService.toUtcInstant(date, window.getEndTime(), participant.getTimezone());
            eventAvailabilityFilter.add(new BookingWindow(start, end));
        }

        // 10. Calendar busy — participant's OWN connections, ALL_CONNECTED semantics
        //     (empty bindings list). The event type's availabilityCalendarsJson / mode
        //     is the owner's config and does NOT apply to participants.
        List<BusyInterval> canonicalBusy =
                calendarBusyTimeService.busyIntervalsForDateCanonical(
                        participantUserId, date, zoneId, List.of());
        List<TimeInterval> calendarBusy = new ArrayList<>(canonicalBusy.size());
        for (BusyInterval interval : canonicalBusy) {
            calendarBusy.add(new TimeInterval(
                    DateTimeUtils.toZone(interval.start(), zoneId),
                    DateTimeUtils.toZone(interval.end(), zoneId)));
        }

        // 11. Build SlotInput — demand-driven (restrictToFilter=false).
        SlotInput input = new SlotInput(
                date,
                zoneId,
                rules,
                override,
                eventType,
                bookingWindows,
                sessionBlockerWindows,
                eventAvailabilityFilter,
                calendarBusy,
                now,
                false);

        // 12. Run engine and return.
        return SlotGenerationEngine.compute(input);
    }
}
