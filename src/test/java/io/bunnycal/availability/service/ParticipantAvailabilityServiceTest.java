package io.bunnycal.availability.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.AvailabilityMode;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Which calendars block a participant's slots.
 *
 * <p>The event type's availability selection is the OWNER's configuration — it names connections
 * only the owner holds — so it can never be applied to another participant. The collective owner is
 * the single exception: they sit on the meeting themselves, and the selection is about their own
 * calendars. Without that exception the picker would be decorative: collected in the wizard and
 * silently ignored by the engine that generates the slots.
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
    @Mock EventTypeOrchestrationJsonCodec orchestrationJsonCodec;
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
                orchestrationJsonCodec, timeConversionService);

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
        lenient().when(calendarBusyTimeService.busyIntervalsForDateCanonical(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void collectiveOwner_isEvaluatedAgainstTheirOwnSelectedCalendars() {
        EventType eventType = collectiveEventType();
        List<EventTypeOrchestrationNormalizer.AvailabilityBinding> selection =
                List.of(new EventTypeOrchestrationNormalizer.AvailabilityBinding(
                        UUID.randomUUID(), "google", "work@example.com"));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(user(ownerId)));
        when(orchestrationJsonCodec.resolveAvailabilityBindings(eventType)).thenReturn(selection);

        service.computeForParticipant(ownerId, eventType, date, Instant.now());

        verify(calendarBusyTimeService).busyIntervalsForDateCanonical(
                eq(ownerId), eq(date), any(), eq(selection));
    }

    @Test
    void otherParticipants_areEvaluatedAgainstAllOfTheirOwnCalendars() {
        EventType eventType = collectiveEventType();
        when(userRepository.findById(memberId)).thenReturn(Optional.of(user(memberId)));

        service.computeForParticipant(memberId, eventType, date, Instant.now());

        // The owner's selection names connections this member does not own, so it must not be
        // applied to them: every calendar they have connected blocks their slots.
        ArgumentCaptor<List<EventTypeOrchestrationNormalizer.AvailabilityBinding>> bindings =
                ArgumentCaptor.forClass(List.class);
        verify(calendarBusyTimeService).busyIntervalsForDateCanonical(
                eq(memberId), eq(date), any(), bindings.capture());
        assertEquals(List.of(), bindings.getValue());
    }

    // Round-robin's owner is not on the booking (the assigned member is) and its wizard collects no
    // availability selection, so the owner exception must not leak into it.
    @Test
    void roundRobinOwner_isNotGivenTheOwnerException() {
        EventType eventType = EventType.builder()
                .id(UUID.randomUUID()).userId(ownerId)
                .kind(EventKind.ROUND_ROBIN)
                .name("RR").slug("rr")
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .availabilityMode(AvailabilityMode.SELECTED)
                .build();
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(user(ownerId)));

        service.computeForParticipant(ownerId, eventType, date, Instant.now());

        ArgumentCaptor<List<EventTypeOrchestrationNormalizer.AvailabilityBinding>> bindings =
                ArgumentCaptor.forClass(List.class);
        verify(calendarBusyTimeService).busyIntervalsForDateCanonical(
                eq(ownerId), eq(date), any(), bindings.capture());
        assertEquals(List.of(), bindings.getValue());
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
                .availabilityMode(AvailabilityMode.SELECTED)
                .build();
    }

    private User user(UUID id) {
        return User.builder().id(id).email(id + "@test.com").timezone("UTC").build();
    }
}
