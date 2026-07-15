package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.dto.AvailabilityStatus;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.service.EventTypeOrchestrationNormalizer;
import io.bunnycal.availability.service.HolidayDayOffService;
import io.bunnycal.availability.service.SlotService;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.domain.AssignmentReason;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.dto.PublicRescheduleRequest;
import io.bunnycal.booking.repository.CalendarEventMappingRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.availability.engine.TimeInterval;
import io.bunnycal.calendar.service.CalendarBusyTimeService;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.sync.FencingTokenGenerator;
import io.bunnycal.common.time.TimeConversionService;
import io.bunnycal.common.time.TimezoneService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublicBookingServiceTest {

    @Mock PublicBookingTargetResolver publicBookingTargetResolver;
    @Mock SlotService slotService;
    @Mock BookingService bookingService;
    @Mock BookingRepository bookingRepository;
    @Mock CalendarBusyTimeService calendarBusyTimeService;
    @Mock CalendarConnectionRepository calendarConnectionRepository;
    @Mock CalendarService calendarService;
    @Mock CalendarEventMappingRepository calendarEventMappingRepository;
    @Mock FencingTokenGenerator fencingTokenGenerator;
    @Mock BookingLifecycleService bookingLifecycleService;
    @Mock GuestCapabilityTokenService guestCapabilityTokenService;
    @Mock io.bunnycal.session.service.SessionService sessionService;
    @Mock io.bunnycal.session.repository.EventSessionRepository eventSessionRepository;
    @Mock io.bunnycal.session.repository.SessionRegistrationRepository sessionRegistrationRepository;
    @Mock RoundRobinSlotTokenService roundRobinSlotTokenService;
    @Mock RoundRobinAssignmentService roundRobinAssignmentService;
    @Mock CollectiveSlotTokenService collectiveSlotTokenService;
    @Mock CollectiveAssignmentService collectiveAssignmentService;
    @Mock io.bunnycal.booking.repository.CollectiveParticipantHoldRepository collectiveParticipantHoldRepository;
    @Mock io.bunnycal.availability.service.ParticipantEligibilityService participantEligibilityService;
    @Mock io.bunnycal.booking.repository.BookingAssignmentRepository bookingAssignmentRepository;
    @Mock io.bunnycal.auth.repository.UserRepository userRepository;
    @Mock io.bunnycal.auth.avatar.ProfileAvatarService profileAvatarService;
    @Mock io.bunnycal.availability.repository.EventTypeParticipantRepository eventTypeParticipantRepository;
    @Mock BookingEventTypeResolver bookingEventTypeResolver;
    @Mock io.bunnycal.billing.entitlement.EntitlementService entitlementService;
    @Mock HolidayDayOffService holidayDayOffService;

    private PublicBookingService service;
    private TimeConversionService timeConversionService;

    private final UUID userId = UUID.randomUUID();
    private final UUID eventTypeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        timeConversionService = new TimeConversionService(new TimezoneService());
        service = new PublicBookingService(
                publicBookingTargetResolver,
                slotService,
                bookingService,
                bookingRepository,
                calendarBusyTimeService,
                calendarConnectionRepository,
                calendarService,
                calendarEventMappingRepository,
                fencingTokenGenerator,
                timeConversionService,
                bookingLifecycleService,
                guestCapabilityTokenService,
                sessionService,
                eventSessionRepository,
                sessionRegistrationRepository,
                roundRobinSlotTokenService,
                roundRobinAssignmentService,
                collectiveSlotTokenService,
                collectiveAssignmentService,
                collectiveParticipantHoldRepository,
                participantEligibilityService,
                bookingAssignmentRepository,
                userRepository,
                profileAvatarService,
                eventTypeParticipantRepository,
                bookingEventTypeResolver,
                entitlementService,
                new SimpleMeterRegistry(),
                14L,
                120L
        );
        ReflectionTestUtils.setField(service, "holidayDayOffService", holidayDayOffService);
        // Default: booking owner is entitled (PROFESSIONAL) so the entitlement gate on the hold
        // path is a pass-through for premium kinds. Tests asserting the un-entitled path override.
        Mockito.lenient().when(entitlementService.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(io.bunnycal.billing.entitlement.PlanCatalog.forTier(
                        io.bunnycal.billing.entitlement.PlanTier.PROFESSIONAL));
        Mockito.lenient().when(calendarBusyTimeService.hasBusyConflict(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(false);
        // Default: every event type lookup returns a published event type so the
        // published gate does not interfere with tests that don't test publish state.
        EventType publishedEventType = EventType.builder()
                .id(eventTypeId)
                .userId(userId)
                .name("Test Event")
                .slug("30min")
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.ONE_ON_ONE).capacity(1)
                .published(true)
                .build();
        Mockito.lenient().when(bookingEventTypeResolver.requireByEventTypeId(
                org.mockito.ArgumentMatchers.any())).thenReturn(publishedEventType);
    }

    // P3: availability is now projection-first. The DB-side calendar_events projection
    // is the single source of truth for busy time. PublicBookingService no longer
    // calls GoogleFreeBusyService.busyIntervals on the availability hot path —
    // that round-trip added 200–500ms of provider latency to every page view and
    // created a TOCTOU race against the eventually-consistent webhook ingestion.
    @Test
    void availability_freshProjection_returnsSlotsWithoutCallingFreebusy() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");
        Instant s2 = Instant.parse("2026-05-10T10:30:00Z");
        Instant e2 = Instant.parse("2026-05-10T11:00:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);
        conn.setLastSyncedAt(Instant.now()); // fresh within SLA
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1), new SlotDto("b", s2, e2))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        // Slots returned as-is — filtering is the projection's job, not live freebusy's.
        assertEquals(2, response.slots().size());
        assertEquals(AvailabilityStatus.AVAILABLE, response.status());
        assertEquals(false, response.degraded());    }

    @Test
    void availability_staleProjection_returnsStaleCalendarData() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);
        // Last sync way older than 120s default SLA → stale.
        conn.setLastSyncedAt(Instant.now().minusSeconds(3600));
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        assertEquals(1, response.slots().size());
        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());    }

    @Test
    void availability_neverSyncedConnection_returnsStaleCalendarData() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);
        // lastSyncedAt deliberately null — never-synced connection.
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());
    }

    /**
     * The production regression: an idle, healthy webhook-backed calendar. The sync scheduler stops
     * polling a connection whose watch channel is live, so nothing ingests while the calendar is
     * quiet and lastSyncedAt freezes. The old age-only check then flagged a perfectly fresh
     * projection as stale within 120s, so the banner showed on essentially every public page.
     */
    @Test
    void availability_liveWatchChannel_isFresh_evenWhenLastSyncedAtIsOld() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);
        // Far past the 120s SLA — but the watch channel is alive, so changes still arrive.
        conn.setLastSyncedAt(Instant.now().minusSeconds(3600));
        conn.setWebhookChannelId("chan-1");
        conn.setWebhookChannelExpiresAt(Instant.now().plusSeconds(3600));
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        assertEquals(AvailabilityStatus.AVAILABLE, response.status());
        assertEquals(false, response.degraded());
    }

    /** An expired watch channel delivers nothing, so freshness falls back to the age check. */
    @Test
    void availability_expiredWatchChannel_withOldLastSyncedAt_isStale() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);
        conn.setLastSyncedAt(Instant.now().minusSeconds(3600));
        conn.setWebhookChannelId("chan-1");
        conn.setWebhookChannelExpiresAt(Instant.now().minusSeconds(60)); // expired
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());
    }

    /** A live watch channel must not rescue a connection that has never ingested anything. */
    @Test
    void availability_liveWatchChannel_butNeverSynced_isStale() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);
        // lastSyncedAt null: nothing has been ingested, so there is no projection to trust yet.
        conn.setWebhookChannelId("chan-1");
        conn.setWebhookChannelExpiresAt(Instant.now().plusSeconds(3600));
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());
    }

    /** A live watch channel must not rescue a REVOKED connection — the projection is frozen. */
    @Test
    void availability_liveWatchChannel_butRevoked_isStale() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.REVOKED);
        conn.setProvider(CalendarProviderType.GOOGLE);
        conn.setLastSyncedAt(Instant.now().minusSeconds(10));
        conn.setWebhookChannelId("chan-1");
        conn.setWebhookChannelExpiresAt(Instant.now().plusSeconds(3600));
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.REVOKED))
                .thenReturn(List.of(conn));
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());
    }

    @Test
    void availability_failedConnection_isExcludedFromActive_returnsCalendarNotConnected() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        // FAILED connections are excluded by the ACTIVE status query
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of());
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 3L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(1, response.slots().size());
        assertEquals(AvailabilityStatus.CALENDAR_NOT_CONNECTED, response.status());
        assertEquals(true, response.degraded());    }

    @Test
    void availability_syncingConnection_isExcludedFromActive_returnsCalendarNotConnected() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        // SYNCING connections are excluded by the ACTIVE status query
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of());
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 0L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(AvailabilityStatus.CALENDAR_NOT_CONNECTED, response.status());
        assertEquals(true, response.degraded());
    }

    @Test
    void confirm_rejectsWhenDbConflictExists() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, userId, 1L)));
        when(bookingRepository.findAnyByIdAndEventTypeId(bookingId, eventTypeId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(1L);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.confirm("koushik", "30min", bookingId));
        assertEquals(ErrorCode.SLOT_UNAVAILABLE, ex.getErrorCode());
        verify(bookingService, never()).confirmHeldBooking(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirm_rejectsHolidayDayOffBeforeConfirmingBooking() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, userId, 1L)));
        when(bookingRepository.findAnyByIdAndEventTypeId(bookingId, eventTypeId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId,
                booking.getStartTime(), booking.getEndTime())).thenReturn(0L);
        when(holidayDayOffService.isDayOffUnlessOverridden(
                eq(userId), eq(LocalDate.of(2026, 5, 10)), eq(java.time.ZoneId.of("UTC"))))
                .thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.confirm("koushik", "30min", bookingId));

        assertEquals(ErrorCode.SLOT_UNAVAILABLE, ex.getErrorCode());
        verify(bookingService, never()).confirmHeldBooking(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmGroup_rejectsHolidayDayOffBeforeConfirmingRegistration() {
        UUID registrationId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant start = Instant.parse("2026-05-10T10:00:00Z");
        var registration = io.bunnycal.session.domain.SessionRegistration.builder()
                .id(registrationId)
                .sessionId(sessionId)
                .hostId(userId)
                .build();
        var session = io.bunnycal.session.domain.EventSession.builder()
                .id(sessionId)
                .hostId(userId)
                .eventTypeId(eventTypeId)
                .startTime(start)
                .endTime(start.plus(Duration.ofMinutes(30)))
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "group")).thenReturn(groupTarget());
        when(sessionRegistrationRepository.findByIdAndHostId(registrationId, userId))
                .thenReturn(Optional.of(registration));
        when(eventSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(holidayDayOffService.isDayOffUnlessOverridden(
                eq(userId), eq(LocalDate.of(2026, 5, 10)), eq(java.time.ZoneId.of("UTC"))))
                .thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.confirm("koushik", "group", registrationId));

        assertEquals(ErrorCode.SLOT_UNAVAILABLE, ex.getErrorCode());
        verify(sessionService, never()).confirmRegistration(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void hold_roundRobin_usesSignedSlotTokenAndAssignmentService() {
        UUID bookingId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Instant start = Instant.parse("2026-05-10T10:00:00Z");
        Instant end = Instant.parse("2026-05-10T10:30:00Z");
        String slotToken = "signed-token";

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(roundRobinTarget());
        when(roundRobinSlotTokenService.verify(slotToken)).thenReturn(
                new RoundRobinSlotTokenService.DecodedSlotToken(
                        userId,
                        eventTypeId,
                        start,
                        end,
                        Instant.now(),
                        List.of(participantId)));
        when(bookingEventTypeResolver.requireByEventTypeId(eventTypeId)).thenReturn(EventType.builder()
                .id(eventTypeId)
                .userId(userId)
                .kind(EventKind.ROUND_ROBIN)
                .duration(Duration.ofMinutes(30))
                .build());
        Booking assignedBooking = Booking.builder()
                .id(bookingId)
                .hostId(participantId)
                .eventTypeId(eventTypeId)
                .startTime(start)
                .endTime(end)
                .build();
        when(roundRobinAssignmentService.assignAndCreateHeldBooking(
                org.mockito.ArgumentMatchers.any(),
                eq(start),
                eq(end),
                eq(List.of(participantId)),
                eq(Duration.ofMinutes(10)),
                eq("guest@example.com"),
                eq("Guest")))
                .thenReturn(new RoundRobinAssignmentService.AssignedRoundRobinBooking(
                        assignedBooking,
                        participantId,
                        AssignmentReason.LEAST_RECENTLY_ASSIGNED));
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, participantId, 1L)));

        var response = service.hold("koushik", "30min", new PublicBookRequest(start, "guest@example.com", "Guest", slotToken));

        assertEquals(bookingId, response.bookingId());
        verify(roundRobinAssignmentService).assignAndCreateHeldBooking(
                org.mockito.ArgumentMatchers.any(),
                eq(start),
                eq(end),
                eq(List.of(participantId)),
                eq(Duration.ofMinutes(10)),
                eq("guest@example.com"),
                eq("Guest"));
        verify(bookingService, never()).createHeldBooking(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void hold_roundRobin_rejectsMismatchedSlotToken() {
        Instant start = Instant.parse("2026-05-10T10:00:00Z");
        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(roundRobinTarget());
        when(roundRobinSlotTokenService.verify("signed-token")).thenReturn(
                new RoundRobinSlotTokenService.DecodedSlotToken(
                        userId,
                        UUID.randomUUID(),
                        start,
                        start.plus(Duration.ofMinutes(30)),
                        Instant.now(),
                        List.of(UUID.randomUUID())));

        CustomException ex = assertThrows(CustomException.class,
                () -> service.hold("koushik", "30min", new PublicBookRequest(start, "guest@example.com", "Guest", "signed-token")));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        verify(roundRobinAssignmentService, never()).assignAndCreateHeldBooking(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cancel_usesBookingVersionAndReturnsCancelledStatus() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingLifecycleService.cancelAsGuest(bookingId, eventTypeId, null)).thenReturn(booking);
        var response = service.cancel("koushik", "30min", bookingId, null);
        assertEquals("CANCELLED", response.status());
        verify(bookingLifecycleService).cancelAsGuest(bookingId, eventTypeId, null);
    }

    @Test
    void manageView_returnsBookingDetails_whenTokenValid() {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.parse("2026-05-10T10:00:00Z");
        Instant end = Instant.parse("2026-05-10T10:30:00Z");
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId).build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingLifecycleService.authorizeGuestManageView(bookingId, eventTypeId, "tok-123"))
                .thenReturn(booking);
        when(bookingRepository.findManageRowByEventType(bookingId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.MeetingRow() {
                    public UUID getBookingId() { return bookingId; }
                    public UUID getEventTypeId() { return eventTypeId; }
                    public String getEventTypeName() { return "30 Minute Meeting"; }
                    public Instant getStartTime() { return start; }
                    public Instant getEndTime() { return end; }
                    public String getBookingStatus() { return "CONFIRMED"; }
                    public String getGuestEmail() { return "guest@example.com"; }
                    public String getGuestName() { return "Guest"; }
                    public String getProvider() { return "google"; }
                    public String getCalendarSyncStatus() { return "SYNCED"; }
                    public String getExternalEventId() { return "evt-1"; }
                    public String getProviderEventUrl() { return "https://example.com/evt-1"; }
                    public String getConferenceUrl() { return "https://meet.example.com/abc"; }
                    public String getExternalLifecycleState() { return "STABLE"; }
                    public String getExternalLifecycleReason() { return null; }
                    public Boolean getReconcileSuppressed() { return false; }
                }));
        when(userRepository.findById(userId)).thenReturn(Optional.of(io.bunnycal.auth.domain.user.User.builder()
                .id(userId)
                .name("Host Name")
                .username("koushik")
                .timezone("UTC")
                .build()));

        var response = service.manageView("koushik", "30min", bookingId, "tok-123");

        verify(bookingLifecycleService).authorizeGuestManageView(bookingId, eventTypeId, "tok-123");
        assertEquals(bookingId, response.bookingId());
        assertEquals("30 Minute Meeting", response.eventTitle());
        assertEquals(30L, response.durationMinutes());
        assertEquals(start, response.startTime());
        assertEquals(end, response.endTime());
        assertEquals("Host Name", response.hostName());
        assertEquals("koushik", response.hostUsername());
        assertEquals("guest@example.com", response.attendeeEmail());
        assertEquals("Guest", response.attendeeName());
        assertEquals("https://meet.example.com/abc", response.conferenceDetails().joinUrl());
        assertEquals("CONFIRMED", response.status());
        assertEquals("STABLE", response.externalLifecycleState());
        assertEquals("UTC", response.timezone());
    }

    @Test
    void manageView_propagatesForbidden_whenTokenInvalid() {
        UUID bookingId = UUID.randomUUID();
        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        Mockito.doThrow(new CustomException(ErrorCode.FORBIDDEN, "Guest capability token is required."))
                .when(bookingLifecycleService)
                .authorizeGuestManageView(bookingId, eventTypeId, "bad-token");

        CustomException ex = assertThrows(CustomException.class,
                () -> service.manageView("koushik", "30min", bookingId, "bad-token"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(bookingRepository, never()).findManageRowByEventType(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void manageView_returnsNotFound_whenBookingMissing() {
        UUID bookingId = UUID.randomUUID();
        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingLifecycleService.authorizeGuestManageView(bookingId, eventTypeId, "tok"))
                .thenReturn(Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId).build());
        when(bookingRepository.findManageRowByEventType(bookingId, eventTypeId))
                .thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> service.manageView("koushik", "30min", bookingId, "tok"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void reschedule_rejectsWhenProjectionBusyOverlaps() {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.parse("2026-05-10T11:00:00Z");
        Instant end = Instant.parse("2026-05-10T11:30:00Z");
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId).build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingLifecycleService.authorizeGuestReschedule(bookingId, eventTypeId, null)).thenReturn(booking);
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, userId, 3L)));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, start, end)).thenReturn(0L);
        when(calendarBusyTimeService.hasBusyConflict(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(start),
                org.mockito.ArgumentMatchers.eq(end),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.reschedule("koushik", "30min", bookingId, new PublicRescheduleRequest(start), null));
        assertEquals(ErrorCode.SLOT_UNAVAILABLE, ex.getErrorCode());
        verify(bookingService, never()).updateBooking(
                org.mockito.ArgumentMatchers.eq(bookingId),
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(start),
                org.mockito.ArgumentMatchers.eq(end),
                org.mockito.ArgumentMatchers.eq(3L));
    }


    @Test
    void confirm_returnsSyncingAndConfirmsBooking() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, userId, 1L)));
        when(bookingRepository.findAnyByIdAndEventTypeId(bookingId, eventTypeId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);

        var response = service.confirm("koushik", "30min", bookingId);

        assertEquals("SYNCING", response.status());
        verify(bookingService).confirmHeldBooking(bookingId);
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirm_roundRobin_usesAssignedParticipantHostForConflictChecks() {
        UUID bookingId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(participantId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(roundRobinTarget());
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, participantId, 1L)));
        when(bookingRepository.findAnyByIdAndEventTypeId(bookingId, eventTypeId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(participantId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);
        when(participantEligibilityService.checkForRoundRobin(participantId))
                .thenReturn(new io.bunnycal.availability.service.ParticipantEligibilityResult(participantId, true, null));

        var response = service.confirm("koushik", "30min", bookingId);

        assertEquals("SYNCING", response.status());
        verify(bookingRepository).countConflictsExcludingBooking(participantId, bookingId, booking.getStartTime(), booking.getEndTime());
        verify(bookingRepository, never()).countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime());
    }

    @Test
    void confirm_missingGuestEmailStillConfirmsAsSyncing() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z"))
                .endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail(" ")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, userId, 1L)));
        when(bookingRepository.findAnyByIdAndEventTypeId(bookingId, eventTypeId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);

        var response = service.confirm("koushik", "30min", bookingId);
        assertEquals("SYNCING", response.status());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    @Test
    void confirm_doesNotFailOnCalendarCreateIssues() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, userId, 1L)));
        when(bookingRepository.findAnyByIdAndEventTypeId(bookingId, eventTypeId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);

        var response = service.confirm("koushik", "30min", bookingId);
        assertEquals("SYNCING", response.status());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    @Test
    void confirm_noLongerReturnsSyncInProgressError() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, userId, 1L)));
        when(bookingRepository.findAnyByIdAndEventTypeId(bookingId, eventTypeId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);

        var response = service.confirm("koushik", "30min", bookingId);
        assertEquals("SYNCING", response.status());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    @Test
    void availability_activeConnectionWithRecentSync_returnsAvailable() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);
        conn.setLastSyncedAt(Instant.now());
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(AvailabilityStatus.AVAILABLE, response.status());
        assertEquals(1, response.slots().size());    }

    @Test
    void availability_noActiveConnection_returnsCalendarNotConnectedDegraded() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of());
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 7L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(AvailabilityStatus.CALENDAR_NOT_CONNECTED, response.status());
        assertEquals(true, response.degraded());
        assertEquals(1, response.slots().size());
        assertEquals("a", response.slots().get(0).slotId());    }

    @Test
    void availability_disconnectedConnection_isExcludedFromActive_returnsCalendarNotConnected() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        // DISCONNECTED connections are excluded by findByUserIdAndStatusOrderByCreatedAtAsc(..., ACTIVE)
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of());
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 8L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(AvailabilityStatus.CALENDAR_NOT_CONNECTED, response.status());
        assertEquals(true, response.degraded());
        assertEquals(1, response.slots().size());
        assertEquals("a", response.slots().get(0).slotId());    }

    @Test
    void confirm_succeedsWithoutCalendarConnection() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, userId, 1L)));
        when(bookingRepository.findAnyByIdAndEventTypeId(bookingId, eventTypeId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);
        var response = service.confirm("koushik", "30min", bookingId);
        assertEquals("SYNCING", response.status());
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    @Test
    void confirm_withActiveConnection_doesNotCallCalendarSynchronously() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, userId, 1L)));
        when(bookingRepository.findAnyByIdAndEventTypeId(bookingId, eventTypeId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);

        var response = service.confirm("koushik", "30min", bookingId);
        assertEquals("SYNCING", response.status());
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    @Test
    void confirm_alwaysUsesAsyncSyncJobPath_doesNotCallCalendarSynchronously() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();
        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndEventTypeId(bookingId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, userId, 1L)));
        when(bookingRepository.findAnyByIdAndEventTypeId(bookingId, eventTypeId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);

        var response = service.confirm("koushik", "30min", bookingId);
        assertEquals("SYNCING", response.status());
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    // ── Fix 2: REVOKED calendar connection must be treated as stale ──────────
    // isProjectionStale() previously did not check REVOKED, so a REVOKED connection
    // with a recent lastSyncedAt would incorrectly appear fresh.

    @Test
    void availability_revokedConnection_withRecentSyncedAt_isStale() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        // Simulate a connection whose status was changed to REVOKED but whose
        // lastSyncedAt is recent (e.g. was synced successfully just before revocation).
        // Before the fix, isProjectionStale() would fall through to the age check
        // and return false (fresh), incorrectly serving availability as non-stale.
        conn.setStatus(CalendarConnectionStatus.REVOKED);
        conn.setProvider(CalendarProviderType.GOOGLE);
        conn.setLastSyncedAt(Instant.now().minusSeconds(10)); // recent — was fresh before fix
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        // REVOKED must always produce stale, regardless of lastSyncedAt freshness.
        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());
    }

    @Test
    void availability_errorConnection_withRecentSyncedAt_isStale() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ERROR);
        conn.setProvider(CalendarProviderType.GOOGLE);
        conn.setLastSyncedAt(Instant.now().minusSeconds(10));
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());
    }

    @Test
    void availability_failedConnection_withRecentSyncedAt_isStale() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.FAILED);
        conn.setProvider(CalendarProviderType.GOOGLE);
        conn.setLastSyncedAt(Instant.now().minusSeconds(10));
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());
    }

    @Test
    void availability_activeConnection_withRecentSyncedAt_isNotStale() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);
        conn.setLastSyncedAt(Instant.now().minusSeconds(10)); // well within 120s SLA
        when(calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        // ACTIVE with recent sync → healthy.
        assertEquals(AvailabilityStatus.AVAILABLE, response.status());
        assertEquals(false, response.degraded());
    }

    private PublicBookingTargetResolver.ResolvedTarget target() {
        return new PublicBookingTargetResolver.ResolvedTarget(
                userId,
                eventTypeId,
                "Host Name",
                "koushik",
                "UTC",
                "host@example.com",
                null,
                "30 Minute Meeting",
                "desc",
                "location",
                Duration.ofMinutes(30),
                Duration.ofMinutes(10),
                io.bunnycal.availability.domain.EventKind.ONE_ON_ONE,
                1
        );
    }

    private PublicBookingTargetResolver.ResolvedTarget roundRobinTarget() {
        return new PublicBookingTargetResolver.ResolvedTarget(
                userId,
                eventTypeId,
                "Host Name",
                "koushik",
                "UTC",
                "host@example.com",
                null,
                "30 Minute Meeting",
                "desc",
                "location",
                Duration.ofMinutes(30),
                Duration.ofMinutes(10),
                EventKind.ROUND_ROBIN,
                1
        );
    }

    private PublicBookingTargetResolver.ResolvedTarget groupTarget() {
        return new PublicBookingTargetResolver.ResolvedTarget(
                userId,
                eventTypeId,
                "Host Name",
                "koushik",
                "UTC",
                "host@example.com",
                null,
                "Group Session",
                "desc",
                "location",
                Duration.ofMinutes(30),
                Duration.ofMinutes(10),
                EventKind.GROUP,
                20
        );
    }

    private BookingRepository.BookingStateRow stateRow(UUID bookingId, UUID hostId, long version) {
        return new BookingRepository.BookingStateRow() {
            public UUID getId() { return bookingId; }
            public UUID getHostId() { return hostId; }
            public String getStatus() { return "PENDING"; }
            public Long getVersion() { return version; }
            public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
            public Long getTerminalIntentEpoch() { return 0L; }
        };
    }
}
