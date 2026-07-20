package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.AvailabilityOverride;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.domain.EventAvailabilityWindow;
import io.bunnycal.availability.domain.EventAvailabilityMode;
import io.bunnycal.availability.domain.EventKind;
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
import java.time.Duration;
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
    private final HolidayDayOffService holidayDayOffService;
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
            HolidayDayOffService holidayDayOffService,
            TimeConversionService timeConversionService) {
        this.userRepository = userRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.availabilityOverrideRepository = availabilityOverrideRepository;
        this.bookingRepository = bookingRepository;
        this.eventSessionRepository = eventSessionRepository;
        this.reservationWindowRepository = reservationWindowRepository;
        this.eventAvailabilityWindowRepository = eventAvailabilityWindowRepository;
        this.calendarBusyTimeService = calendarBusyTimeService;
        this.holidayDayOffService = holidayDayOffService;
        this.timeConversionService = timeConversionService;
    }

    /**
     * Computes the slot candidates contributed by {@code participantUserId} for the
     * given event type and date.
     *
     * <p>Everything is scoped to the participant — rules, override, bookings, sessions, calendar
     * busy. Nothing about which calendars block them is read from the event type: that is their own
     * setting, and it is the same whoever is doing the booking.
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

        // 4. Load participant's own override (nullable). A public holiday on the participant's own
        //    holiday calendar makes their whole day off — so a member on holiday drops out of that
        //    day's round-robin or collective. Their explicit override wins, same as for a host.
        AvailabilityOverride override =
                availabilityOverrideRepository.findByUserIdAndDate(participantUserId, date).orElse(null);
        if (override == null && holidayDayOffService.isDayOff(participantUserId, date, zoneId)) {
            override = AvailabilityOverride.builder()
                    .userId(participantUserId)
                    .date(date)
                    .isAvailable(false)
                    .build();
        }

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

        // 7b. Origin holds: an hour this participant vacated by rescheduling a session, and chose
        //     to keep blocked. Round robin and collective build their slots here rather than from
        //     the host's own availability, so a hold applied only in SlotService would leave the
        //     hour open on exactly the multi-host events most likely to fill it.
        for (EventSession moved : eventSessionRepository.findOriginHoldsInRange(
                participantUserId, dayStartUtc, dayEndUtc)) {
            Instant occurrenceStart = moved.getScheduledOccurrenceStart();
            sessionBlockerWindows.add(new BookingWindow(
                    occurrenceStart,
                    occurrenceStart.plus(Duration.between(moved.getStartTime(), moved.getEndTime()))));
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

        // 9. Multi-host event operating window. CUSTOM constrains this participant's own
        //    availability; it never manufactures time outside their personal schedule.
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

        // 10. Calendar busy — the participant's own calendars, the ones they flagged as blocking.
        //
        //     Everyone is evaluated identically, owner included. Availability is a property of a
        //     calendar, so the answer cannot depend on whose event is being booked. (It used to: the
        //     owner's wizard selection applied to the owner alone, and every other participant was
        //     evaluated with "all your calendars block you" — the same calendar, two rules, and no
        //     way for the participant to reach the one being applied to them.)
        List<BusyInterval> canonicalBusy =
                calendarBusyTimeService.busyIntervalsForDateCanonical(participantUserId, date, zoneId);
        List<TimeInterval> calendarBusy = new ArrayList<>(canonicalBusy.size());
        for (BusyInterval interval : canonicalBusy) {
            calendarBusy.add(new TimeInterval(
                    DateTimeUtils.toZone(interval.start(), zoneId),
                    DateTimeUtils.toZone(interval.end(), zoneId)));
        }

        // 11. CUSTOM is an event-level operating window, but participant availability
        // remains the base. A missing custom window for this day therefore means closed.
        boolean customSchedule = eventType.getAvailabilityMode() == EventAvailabilityMode.CUSTOM;
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
                customSchedule,
                false);

        // 12. Run engine and return.
        return SlotGenerationEngine.compute(input);
    }
}
