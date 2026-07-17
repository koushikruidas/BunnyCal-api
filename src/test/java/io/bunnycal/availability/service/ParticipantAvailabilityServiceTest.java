package io.bunnycal.availability.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.AvailabilityOverrideRepository;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.EventAvailabilityWindowRepository;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.calendar.service.CalendarBusyTimeService;
import io.bunnycal.common.time.TimeConversionService;
import io.bunnycal.session.repository.EventSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Which calendars block a participant's slots.
 *
 * <p>Every participant — the owner included — is evaluated against <b>their own</b> calendars, and
 * only their own: the set of calendars that block is a property of the user (each connection's
 * {@code checks_availability} flag), never of the event type. There is no per-event-type selection
 * that could leak the owner's connections onto a member who does not hold them.
 */
@ExtendWith(MockitoExtension.class)
class ParticipantAvailabilityServiceTest {

    @Mock UserRepository userRepository;
    @Mock AvailabilityRuleRepository availabilityRuleRepository;
    @Mock AvailabilityOverrideRepository availabilityOverrideRepository;
    @Mock BookingRepository bookingRepository;
    @Mock EventSessionRepository eventSessionRepository;
    @Mock GroupEventReservationWindowRepository reservationWindowRepository;
    @Mock EventAvailabilityWindowRepository eventAvailabilityWindowRepository;
    @Mock CalendarBusyTimeService calendarBusyTimeService;
    @Mock HolidayDayOffService holidayDayOffService;
    @Mock TimeConversionService timeConversionService;

    private ParticipantAvailabilityService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 5, 11); // Monday

    @BeforeEach
    void setUp() {
        service = new ParticipantAvailabilityService(
                userRepository, availabilityRuleRepository, availabilityOverrideRepository,
                bookingRepository, eventSessionRepository, reservationWindowRepository,
                eventAvailabilityWindowRepository, calendarBusyTimeService,
                holidayDayOffService, timeConversionService);

        lenient().when(timeConversionService.resolveZone(any())).thenReturn(ZoneId.of("UTC"));
        lenient().when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(any()))
                .thenReturn(List.of());
        lenient().when(availabilityOverrideRepository.findByUserIdAndDate(any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(bookingRepository.findActiveOverlappingBookings(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(reservationWindowRepository.findBlockingCandidatesForDate(any(), any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(eventAvailabilityWindowRepository.findOwnWindowsForDay(any(), any()))
                .thenReturn(List.of());
        lenient().when(calendarBusyTimeService.busyIntervalsForDateCanonical(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void collectiveOwner_isEvaluatedAgainstTheirOwnCalendars() {
        EventType eventType = collectiveEventType();
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(user(ownerId)));

        service.computeForParticipant(ownerId, eventType, date, Instant.now());

        verify(calendarBusyTimeService).busyIntervalsForDateCanonical(eq(ownerId), eq(date), any());
    }

    @Test
    void otherParticipants_areEvaluatedAgainstTheirOwnCalendars_notTheOwners() {
        EventType eventType = collectiveEventType();
        when(userRepository.findById(memberId)).thenReturn(Optional.of(user(memberId)));

        service.computeForParticipant(memberId, eventType, date, Instant.now());

        // The member's own calendars block their slots. The owner's are never consulted for them.
        verify(calendarBusyTimeService).busyIntervalsForDateCanonical(eq(memberId), eq(date), any());
        verify(calendarBusyTimeService, never()).busyIntervalsForDateCanonical(eq(ownerId), any(), any());
    }

    // Round-robin's owner is not on the booking (the assigned member is), so nothing about the owner
    // may bleed into a participant's availability.
    @Test
    void roundRobinParticipant_isEvaluatedAgainstTheirOwnCalendars() {
        EventType eventType = EventType.builder()
                .id(UUID.randomUUID()).userId(ownerId)
                .kind(EventKind.ROUND_ROBIN)
                .name("RR").slug("rr")
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .build();
        when(userRepository.findById(memberId)).thenReturn(Optional.of(user(memberId)));

        service.computeForParticipant(memberId, eventType, date, Instant.now());

        verify(calendarBusyTimeService).busyIntervalsForDateCanonical(eq(memberId), eq(date), any());
        verify(calendarBusyTimeService, never()).busyIntervalsForDateCanonical(eq(ownerId), any(), any());
    }

    private EventType collectiveEventType() {
        return EventType.builder()
                .id(UUID.randomUUID()).userId(ownerId)
                .kind(EventKind.COLLECTIVE)
                .name("Team Sync").slug("team-sync")
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .build();
    }

    private User user(UUID id) {
        return User.builder().id(id).email(id + "@test.com").timezone("UTC").build();
    }
}
