package io.bunnycal.session.occurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.RecurrenceFrequency;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.repository.DbClockRepository;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.availability.service.HolidayDayOffService;
import io.bunnycal.availability.service.SlotService;
import io.bunnycal.billing.entitlement.EntitlementService;
import io.bunnycal.billing.entitlement.Entitlements;
import io.bunnycal.billing.entitlement.Feature;
import io.bunnycal.billing.entitlement.PlanTier;
import io.bunnycal.common.time.TimeConversionService;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.domain.SessionDetachedReason;
import io.bunnycal.session.domain.SessionStatus;
import io.bunnycal.session.repository.EventSessionRepository;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupOccurrenceResolverTest {

    private static final UUID HOST_ID = UUID.randomUUID();
    private static final UUID EVENT_TYPE_ID = UUID.randomUUID();
    private static final UUID WINDOW_ID = UUID.randomUUID();
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 3);
    private static final Instant NINE = Instant.parse("2026-08-03T09:00:00Z");
    private static final Instant TEN = Instant.parse("2026-08-03T10:00:00Z");

    private final SlotService slotService = mock(SlotService.class);
    private final GroupEventReservationWindowRepository windowRepository =
            mock(GroupEventReservationWindowRepository.class);
    private final EventSessionRepository sessionRepository = mock(EventSessionRepository.class);
    private final DbClockRepository clock = mock(DbClockRepository.class);
    private final HolidayDayOffService holidayService = mock(HolidayDayOffService.class);
    private final EntitlementService entitlementService = mock(EntitlementService.class);
    private final TimeConversionService timeConversionService = mock(TimeConversionService.class);

    private GroupOccurrenceResolver resolver;
    private EventType eventType;

    @BeforeEach
    void setUp() {
        resolver = new GroupOccurrenceResolver(slotService, windowRepository, sessionRepository,
                clock, holidayService, entitlementService, timeConversionService);
        eventType = EventType.builder()
                .id(EVENT_TYPE_ID)
                .userId(HOST_ID)
                .name("Workshop")
                .slug("workshop")
                .kind(EventKind.GROUP)
                .published(true)
                .duration(Duration.ofHours(1))
                .slotInterval(Duration.ofHours(1))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .minNotice(Duration.ofHours(2))
                .maxAdvance(Duration.ofDays(1))
                .holdDuration(Duration.ofMinutes(10))
                .capacity(5)
                .build();
        when(timeConversionService.resolveZone("UTC")).thenReturn(ZoneId.of("UTC"));
        when(clock.now()).thenReturn(Instant.parse("2026-07-01T00:00:00Z"));
        when(entitlementService.resolve(HOST_ID)).thenReturn(new Entitlements(
                PlanTier.PROFESSIONAL, Set.of(Feature.GROUP_EVENT), Map.of()));
        when(windowRepository.findByEventTypeId(EVENT_TYPE_ID)).thenReturn(List.of(window()));
        when(holidayService.isDayOffUnlessOverridden(any(), any(), any())).thenReturn(false);
        when(slotService.getSlots(any())).thenReturn(slotResponse(List.of(slot(NINE, TEN))));
    }

    @Test
    void ordinaryOccurrenceUsesSlotServiceBookability() {
        when(sessionRepository.findEffectiveOccurrenceSessionsInRange(any(), any(), any()))
                .thenReturn(List.of());

        List<EffectiveGroupOccurrence> result = resolver.resolveRange(
                HOST_ID, eventType, "UTC", MONDAY, 1);

        assertThat(result).singleElement().satisfies(occurrence -> {
            assertThat(occurrence.originalOccurrence()).isEqualTo(NINE);
            assertThat(occurrence.effectiveStart()).isEqualTo(NINE);
            assertThat(occurrence.placement()).isEqualTo(OccurrencePlacement.RULE_DERIVED);
            assertThat(occurrence.isBookable()).isTrue();
        });
    }

    @Test
    void movedOccurrenceWaivesGridAndMaxAdvanceButKeepsStableIdentity() {
        Instant movedStart = Instant.parse("2026-08-03T14:00:00Z");
        EventSession moved = movedSession(movedStart, SessionStatus.OPEN, 1);
        when(sessionRepository.findEffectiveOccurrenceSessionsInRange(any(), any(), any()))
                .thenReturn(List.of(moved));
        when(slotService.getSlots(any())).thenReturn(slotResponse(List.of()));

        List<EffectiveGroupOccurrence> result = resolver.resolveRange(
                HOST_ID, eventType, "UTC", MONDAY, 1);

        assertThat(result).singleElement().satisfies(occurrence -> {
            assertThat(occurrence.originalOccurrence()).isEqualTo(NINE);
            assertThat(occurrence.effectiveStart()).isEqualTo(movedStart);
            assertThat(occurrence.sessionId()).isEqualTo(moved.getId());
            assertThat(occurrence.placement()).isEqualTo(OccurrencePlacement.HOST_MOVED);
            assertThat(occurrence.isBookable()).isTrue();
        });
    }

    @Test
    void cancelledMovedOccurrenceConsumesOriginWithoutRenderingDestinationOutsideRange() {
        Instant nextWeek = NINE.plus(Duration.ofDays(7));
        EventSession cancelled = movedSession(nextWeek, SessionStatus.CANCELLED, 0);
        when(sessionRepository.findEffectiveOccurrenceSessionsInRange(any(), any(), any()))
                .thenReturn(List.of(cancelled));

        List<EffectiveGroupOccurrence> result = resolver.resolveRange(
                HOST_ID, eventType, "UTC", MONDAY, 1);

        assertThat(result).isEmpty();
    }

    private GroupEventReservationWindow window() {
        return GroupEventReservationWindow.builder()
                .id(WINDOW_ID)
                .eventTypeId(EVENT_TYPE_ID)
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(10, 0))
                .scheduleType(ScheduleType.RECURRING)
                .frequency(RecurrenceFrequency.WEEKLY)
                .startDate(LocalDate.of(2026, 1, 1))
                .recurrenceEndMode(RecurrenceEndMode.NONE)
                .build();
    }

    private EventSession movedSession(Instant actualStart, SessionStatus status, int confirmed) {
        return EventSession.builder()
                .id(UUID.randomUUID())
                .hostId(HOST_ID)
                .eventTypeId(EVENT_TYPE_ID)
                .reservationWindowId(WINDOW_ID)
                .scheduledOccurrenceStart(NINE)
                .startTime(actualStart)
                .endTime(actualStart.plus(Duration.ofHours(1)))
                .detachedAt(Instant.parse("2026-07-01T00:00:00Z"))
                .detachedReason(SessionDetachedReason.HOST_RESCHEDULED)
                .status(status)
                .capacity(5)
                .confirmedCount(confirmed)
                .build();
    }

    private SlotDto slot(Instant start, Instant end) {
        return new SlotDto(start.toString(), start, end, true, null);
    }

    private SlotResponse slotResponse(List<SlotDto> slots) {
        return new SlotResponse(HOST_ID, EVENT_TYPE_ID, MONDAY, "UTC", 1L, Instant.EPOCH,
                false, slots, io.bunnycal.availability.dto.AvailabilityStatus.AVAILABLE);
    }
}
