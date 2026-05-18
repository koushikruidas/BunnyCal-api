package com.daedalussystems.easySchedule.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.availability.dto.AvailabilityStatus;
import com.daedalussystems.easySchedule.availability.dto.SlotDto;
import com.daedalussystems.easySchedule.availability.dto.SlotResponse;
import com.daedalussystems.easySchedule.availability.service.SlotService;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.domain.BookingId;
import com.daedalussystems.easySchedule.booking.dto.PublicRescheduleRequest;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.availability.engine.TimeInterval;
import com.daedalussystems.easySchedule.calendar.service.CalendarBusyTimeService;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.FencingTokenGenerator;
import com.daedalussystems.easySchedule.common.time.TimeConversionService;
import com.daedalussystems.easySchedule.common.time.TimezoneService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.Mockito;

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
                new SimpleMeterRegistry(),
                14L,
                false,
                120L
        );
        Mockito.lenient().when(calendarBusyTimeService.busyIntervalsForDate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
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
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE))
                .thenReturn(Optional.of(conn));
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
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE))
                .thenReturn(Optional.of(conn));
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
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE))
                .thenReturn(Optional.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));

        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());
    }

    @Test
    void availability_failedConnectionComputesDegradedSlots() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.FAILED);
        conn.setProvider(CalendarProviderType.GOOGLE);
        // Even with a recent lastSyncedAt, FAILED status forces STALE_CALENDAR_DATA.
        conn.setLastSyncedAt(Instant.now());
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE))
                .thenReturn(Optional.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 3L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(1, response.slots().size());
        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());
        assertEquals(3L, response.version());    }

    @Test
    void availability_syncingConnectionStillReturnsSyncInProgress() {

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.SYNCING);
        conn.setProvider(CalendarProviderType.GOOGLE);
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE)).thenReturn(Optional.of(conn));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(AvailabilityStatus.CALENDAR_SYNC_IN_PROGRESS, response.status());
        assertEquals(true, response.degraded());
        assertEquals(0L, response.version());
        verify(slotService, never()).getSlots(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirm_rejectsWhenDbConflictExists() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, userId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
                    public UUID getId() { return bookingId; }
                    public UUID getHostId() { return userId; }
                    public String getStatus() { return "PENDING"; }
                    public Long getVersion() { return 1L; }
                    public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
                    public Long getTerminalIntentEpoch() { return 0L; }
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(1L);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.confirm("koushik", "30min", bookingId));
        assertEquals(ErrorCode.SLOT_UNAVAILABLE, ex.getErrorCode());
        verify(bookingService, never()).confirmHeldBooking(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cancel_usesBookingVersionAndReturnsCancelledStatus() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingLifecycleService.cancelAsGuest(bookingId, userId, eventTypeId, null)).thenReturn(booking);
        var response = service.cancel("koushik", "30min", bookingId, null);
        assertEquals("CANCELLED", response.status());
        verify(bookingLifecycleService).cancelAsGuest(bookingId, userId, eventTypeId, null);
    }

    @Test
    void reschedule_rejectsWhenProjectionBusyOverlaps() {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.parse("2026-05-10T11:00:00Z");
        Instant end = Instant.parse("2026-05-10T11:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, userId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
                    public UUID getId() { return bookingId; }
                    public UUID getHostId() { return userId; }
                    public String getStatus() { return "PENDING"; }
                    public Long getVersion() { return 3L; }
                    public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
                    public Long getTerminalIntentEpoch() { return 0L; }
                }));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, start, end)).thenReturn(0L);
        when(calendarBusyTimeService.busyIntervalsForDate(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new TimeInterval(
                        Instant.parse("2026-05-10T11:10:00Z").atZone(java.time.ZoneId.of("UTC")),
                        Instant.parse("2026-05-10T11:40:00Z").atZone(java.time.ZoneId.of("UTC"))
                )));

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
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, userId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
                    public UUID getId() { return bookingId; }
                    public UUID getHostId() { return userId; }
                    public String getStatus() { return "PENDING"; }
                    public Long getVersion() { return 1L; }
                    public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
                    public Long getTerminalIntentEpoch() { return 0L; }
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);

        var response = service.confirm("koushik", "30min", bookingId);

        assertEquals("SYNCING", response.status());
        verify(bookingService).confirmHeldBooking(bookingId);
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
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
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, userId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
                    public UUID getId() { return bookingId; }
                    public UUID getHostId() { return userId; }
                    public String getStatus() { return "PENDING"; }
                    public Long getVersion() { return 1L; }
                    public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
                    public Long getTerminalIntentEpoch() { return 0L; }
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
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
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, userId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
                    public UUID getId() { return bookingId; }
                    public UUID getHostId() { return userId; }
                    public String getStatus() { return "PENDING"; }
                    public Long getVersion() { return 1L; }
                    public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
                    public Long getTerminalIntentEpoch() { return 0L; }
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
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
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, userId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
                    public UUID getId() { return bookingId; }
                    public UUID getHostId() { return userId; }
                    public String getStatus() { return "PENDING"; }
                    public Long getVersion() { return 1L; }
                    public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
                    public Long getTerminalIntentEpoch() { return 0L; }
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);

        var response = service.confirm("koushik", "30min", bookingId);
        assertEquals("SYNCING", response.status());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    @Test
    void availability_syncingConnectionComputesWhenProviderOptionalEnabled() {
        PublicBookingService providerOptionalService = new PublicBookingService(
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
                new SimpleMeterRegistry(),
                14L,
                true,
                120L
        );
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.SYNCING);
        conn.setProvider(CalendarProviderType.GOOGLE);
        // SYNCING but already synced once recently → projection is fresh enough
        // to serve. Without lastSyncedAt set, P3 would correctly classify as
        // STALE_CALENDAR_DATA (never synced).
        conn.setLastSyncedAt(Instant.now());
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE)).thenReturn(Optional.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = providerOptionalService.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(AvailabilityStatus.AVAILABLE, response.status());
        assertEquals(1, response.slots().size());    }

    @Test
    void availability_missingConnectionComputesDegradedCalendarNotConnectedWhenProviderOptionalEnabled() {
        PublicBookingService providerOptionalService = new PublicBookingService(
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
                new SimpleMeterRegistry(),
                14L,
                true,
                120L
        );
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE))
                .thenReturn(Optional.empty());
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 7L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = providerOptionalService.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(AvailabilityStatus.CALENDAR_NOT_CONNECTED, response.status());
        assertEquals(true, response.degraded());
        assertEquals(1, response.slots().size());
        assertEquals("a", response.slots().get(0).slotId());    }

    @Test
    void availability_disconnectedConnectionComputesDegradedCalendarNotConnectedWhenProviderOptionalEnabled() {
        PublicBookingService providerOptionalService = new PublicBookingService(
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
                new SimpleMeterRegistry(),
                14L,
                true,
                120L
        );
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.DISCONNECTED);
        conn.setProvider(CalendarProviderType.GOOGLE);
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE))
                .thenReturn(Optional.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 8L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = providerOptionalService.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(AvailabilityStatus.CALENDAR_NOT_CONNECTED, response.status());
        assertEquals(true, response.degraded());
        assertEquals(1, response.slots().size());
        assertEquals("a", response.slots().get(0).slotId());    }

    @Test
    void confirm_succeedsWithoutCalendarWhenProviderOptionalEnabled() {
        PublicBookingService providerOptionalService = new PublicBookingService(
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
                new SimpleMeterRegistry(),
                14L,
                true,
                120L
        );
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, userId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
                    public UUID getId() { return bookingId; }
                    public UUID getHostId() { return userId; }
                    public String getStatus() { return "PENDING"; }
                    public Long getVersion() { return 1L; }
                    public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
                    public Long getTerminalIntentEpoch() { return 0L; }
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);
        var response = providerOptionalService.confirm("koushik", "30min", bookingId);
        assertEquals("SYNCING", response.status());
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    @Test
    void confirm_optionalModeWithActiveConnection_doesNotCallCalendarSynchronously() {
        PublicBookingService providerOptionalService = new PublicBookingService(
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
                new SimpleMeterRegistry(),
                14L,
                true,
                120L
        );
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, userId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
                    public UUID getId() { return bookingId; }
                    public UUID getHostId() { return userId; }
                    public String getStatus() { return "PENDING"; }
                    public Long getVersion() { return 1L; }
                    public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
                    public Long getTerminalIntentEpoch() { return 0L; }
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);

        var response = providerOptionalService.confirm("koushik", "30min", bookingId);
        assertEquals("SYNCING", response.status());
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    @Test
    void confirm_asyncMode_skipsSynchronousCalendarCallsAndReturnsSyncing() {
        PublicBookingService asyncService = new PublicBookingService(
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
                new SimpleMeterRegistry(),
                14L,
                false,
                120L
        );
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z"))
                .guestEmail("guest@example.com")
                .build();
        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, userId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
                    public UUID getId() { return bookingId; }
                    public UUID getHostId() { return userId; }
                    public String getStatus() { return "PENDING"; }
                    public Long getVersion() { return 1L; }
                    public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
                    public Long getTerminalIntentEpoch() { return 0L; }
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));

        var response = asyncService.confirm("koushik", "30min", bookingId);
        assertEquals("SYNCING", response.status());
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());        verify(bookingService).confirmHeldBooking(bookingId);
    }

    private PublicBookingTargetResolver.ResolvedTarget target() {
        return new PublicBookingTargetResolver.ResolvedTarget(
                userId,
                eventTypeId,
                "Host Name",
                "koushik",
                "UTC",
                "host@example.com",
                "30 Minute Meeting",
                "desc",
                "location",
                Duration.ofMinutes(30),
                Duration.ofMinutes(10)
        );
    }
}
