package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.RecurrenceFrequency;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.availability.service.SlotService;
import io.bunnycal.booking.dto.PublicGroupSessionCardResponse;
import io.bunnycal.booking.dto.PublicGroupSessionsResponse;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.domain.SessionStatus;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import io.bunnycal.common.time.TimeConversionService;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for a host cancelling ONE session of a recurring group event.
 *
 * <p>The recurrence rule keeps emitting the occurrence after the session on it is cancelled, and
 * {@link SlotService} derives bookable times from the rule alone — it never reads session state.
 * The grid therefore has to apply the terminal-status check itself; when it did not, the slot was
 * advertised as bookable and the click failed at {@code joinSession} with {@code SESSION_CANCELLED},
 * because that path resolves the cancelled row by exact start time.
 */
class PublicGroupSessionCancelledSlotTest {

    private static final UUID HOST_ID = UUID.randomUUID();
    private static final UUID EVENT_TYPE_ID = UUID.randomUUID();
    private static final String TZ = "UTC";
    private static final LocalDate DATE = LocalDate.of(2026, 8, 3); // a Monday
    private static final Instant SLOT_START = Instant.parse("2026-08-03T10:00:00Z");
    private static final Instant SLOT_END = Instant.parse("2026-08-03T11:00:00Z");

    private final PublicBookingTargetResolver targetResolver = mock(PublicBookingTargetResolver.class);
    private final BookingEventTypeResolver eventTypeResolver = mock(BookingEventTypeResolver.class);
    private final SlotService slotService = mock(SlotService.class);
    private final GroupEventReservationWindowRepository windowRepository =
            mock(GroupEventReservationWindowRepository.class);
    private final EventSessionRepository sessionRepository = mock(EventSessionRepository.class);
    private final SessionRegistrationRepository registrationRepository =
            mock(SessionRegistrationRepository.class);
    private final TimeConversionService timeConversionService = mock(TimeConversionService.class);

    private PublicGroupSessionQueryService service;

    @BeforeEach
    void setUp() {
        service = new PublicGroupSessionQueryService(targetResolver, eventTypeResolver, slotService,
                windowRepository, sessionRepository, registrationRepository, timeConversionService);

        when(targetResolver.resolve(any(), any())).thenReturn(new PublicBookingTargetResolver.ResolvedTarget(
                HOST_ID, EVENT_TYPE_ID, "Host", "host", TZ, "host@example.com", null,
                "Group Hours 2", null, null, Duration.ofHours(1), Duration.ofMinutes(10),
                EventKind.GROUP, 5));

        when(eventTypeResolver.requireByEventTypeId(EVENT_TYPE_ID)).thenReturn(
                EventType.builder()
                        .id(EVENT_TYPE_ID).name("Group Hours 2").slug("group-hours-2")
                        .kind(EventKind.GROUP).capacity(5).published(true)
                        .duration(Duration.ofHours(1))
                        .slotInterval(Duration.ofHours(1))
                        .build());

        when(timeConversionService.resolveZone(TZ)).thenReturn(ZoneId.of(TZ));
        lenient().when(timeConversionService.dayStartUtc(any(), any()))
                .thenAnswer(i -> ((LocalDate) i.getArgument(0)).atStartOfDay(ZoneId.of(TZ)).toInstant());

        when(windowRepository.findByEventTypeId(EVENT_TYPE_ID)).thenReturn(List.of(
                GroupEventReservationWindow.builder()
                        .id(UUID.randomUUID())
                        .eventTypeId(EVENT_TYPE_ID)
                        .dayOfWeek(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(10, 0))
                        .endTime(LocalTime.of(11, 0))
                        .scheduleType(ScheduleType.RECURRING)
                        .frequency(RecurrenceFrequency.WEEKLY)
                        .startDate(LocalDate.of(2026, 1, 1))
                        .recurrenceEndMode(RecurrenceEndMode.NONE)
                        .build()));

        lenient().when(sessionRepository.findMovedSessionsByOccurrenceRange(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(registrationRepository.findConfirmedBySessionIds(any())).thenReturn(List.of());

        // The rule still emits this occurrence — SlotService has no notion of session status.
        lenient().when(slotService.getSlots(any())).thenAnswer(invocation -> slotResponse(List.of(slot(SLOT_START, SLOT_END))));
    }

    @Test
    void cancelledSession_isNotOfferedAsBookable() {
        when(sessionRepository.findByEventTypeIdAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                any(), any(), any()))
                .thenReturn(List.of(session(SessionStatus.CANCELLED, 0)));

        PublicGroupSessionsResponse response = service.getGroupSessions("host", "group-hours-2", DATE, 1);

        List<PublicGroupSessionCardResponse> cards = response.dates().get(0).sessions();
        assertTrue(cards.stream().noneMatch(PublicGroupSessionCardResponse::bookable),
                "a cancelled session must never be advertised as bookable — booking it throws SESSION_CANCELLED");
    }

    @Test
    void openSessionOnTheSameSlot_isStillBookable() {
        // Guards against over-correcting: the ordinary case must keep working.
        when(sessionRepository.findByEventTypeIdAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                any(), any(), any()))
                .thenReturn(List.of(session(SessionStatus.OPEN, 2)));

        PublicGroupSessionsResponse response = service.getGroupSessions("host", "group-hours-2", DATE, 1);

        List<PublicGroupSessionCardResponse> cards = response.dates().get(0).sessions();
        assertEquals(1, cards.size());
        assertTrue(cards.get(0).bookable());
        assertEquals(3, cards.get(0).spotsLeft());
    }

    @Test
    void slotWithNoMaterializedSession_isStillBookable() {
        // The common path: nobody has booked yet, so no row exists at all.
        when(sessionRepository.findByEventTypeIdAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                any(), any(), any()))
                .thenReturn(List.of());

        PublicGroupSessionsResponse response = service.getGroupSessions("host", "group-hours-2", DATE, 1);

        List<PublicGroupSessionCardResponse> cards = response.dates().get(0).sessions();
        assertEquals(1, cards.size());
        assertTrue(cards.get(0).bookable());
    }

    @Test
    void completedSession_isNotOfferedAsBookable() {
        when(sessionRepository.findByEventTypeIdAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                any(), any(), any()))
                .thenReturn(List.of(session(SessionStatus.COMPLETED, 4)));

        PublicGroupSessionsResponse response = service.getGroupSessions("host", "group-hours-2", DATE, 1);

        assertTrue(response.dates().get(0).sessions().stream()
                        .noneMatch(PublicGroupSessionCardResponse::bookable),
                "a finished session must not accept new registrations");
    }

    @Test
    void cancellingOneOccurrence_leavesTheNextWeekBookable() {
        // The whole point of cancelling a single session: the series continues.
        when(sessionRepository.findByEventTypeIdAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                any(), any(), any()))
                .thenReturn(List.of(session(SessionStatus.CANCELLED, 0)));

        Instant nextWeekStart = SLOT_START.plus(Duration.ofDays(7));
        Instant nextWeekEnd = SLOT_END.plus(Duration.ofDays(7));
        lenient().when(slotService.getSlots(any())).thenAnswer(invocation -> {
            LocalDate requested = ((SlotRequest) invocation.getArgument(0)).date();
            if (DATE.equals(requested)) {
                return slotResponse(List.of(slot(SLOT_START, SLOT_END)));
            }
            if (DATE.plusDays(7).equals(requested)) {
                return slotResponse(List.of(slot(nextWeekStart, nextWeekEnd)));
            }
            return slotResponse(List.of());
        });

        PublicGroupSessionsResponse response = service.getGroupSessions("host", "group-hours-2", DATE, 8);

        boolean cancelledDayBookable = response.dates().get(0).sessions().stream()
                .anyMatch(PublicGroupSessionCardResponse::bookable);
        boolean nextWeekBookable = response.dates().get(7).sessions().stream()
                .anyMatch(PublicGroupSessionCardResponse::bookable);

        assertFalse(cancelledDayBookable, "the cancelled occurrence stays closed");
        assertTrue(nextWeekBookable, "cancelling one session must not close the rest of the series");
    }

    private static SlotDto slot(Instant start, Instant end) {
        return new SlotDto(start.toString(), start, end, true, null);
    }

    private static SlotResponse slotResponse(List<SlotDto> slots) {
        return new SlotResponse(HOST_ID, EVENT_TYPE_ID, DATE, TZ, 1L, Instant.EPOCH, false,
                slots, io.bunnycal.availability.dto.AvailabilityStatus.AVAILABLE);
    }

    private static EventSession session(SessionStatus status, int confirmedCount) {
        return EventSession.builder()
                .id(UUID.randomUUID())
                .hostId(HOST_ID)
                .eventTypeId(EVENT_TYPE_ID)
                .startTime(SLOT_START)
                .endTime(SLOT_END)
                .scheduledOccurrenceStart(SLOT_START)
                .capacity(5)
                .confirmedCount(confirmedCount)
                .status(status)
                .build();
    }
}
