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
import com.daedalussystems.easySchedule.calendar.service.GoogleFreeBusyService;
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

@ExtendWith(MockitoExtension.class)
class PublicBookingServiceTest {

    @Mock PublicBookingTargetResolver publicBookingTargetResolver;
    @Mock SlotService slotService;
    @Mock BookingService bookingService;
    @Mock BookingRepository bookingRepository;
    @Mock GoogleFreeBusyService freeBusyService;
    @Mock CalendarConnectionRepository calendarConnectionRepository;
    @Mock CalendarService calendarService;
    @Mock CalendarEventMappingRepository calendarEventMappingRepository;
    @Mock FencingTokenGenerator fencingTokenGenerator;

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
                freeBusyService,
                calendarConnectionRepository,
                calendarService,
                calendarEventMappingRepository,
                fencingTokenGenerator,
                timeConversionService,
                false
        );
    }

    @Test
    void availability_filtersSlotsOverlappingBusyIntervals() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");
        Instant s2 = Instant.parse("2026-05-10T10:30:00Z");
        Instant e2 = Instant.parse("2026-05-10T11:00:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE)).thenReturn(Optional.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1), new SlotDto("b", s2, e2))
        ));
        when(freeBusyService.busyIntervals(userId,
                Instant.parse("2026-05-10T00:00:00Z"),
                Instant.parse("2026-05-11T00:00:00Z")))
                .thenReturn(List.of(new GoogleFreeBusyService.BusyInterval(
                        Instant.parse("2026-05-10T10:15:00Z"),
                        Instant.parse("2026-05-10T10:45:00Z")
                )));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(0, response.slots().size());
        assertEquals(AvailabilityStatus.NO_SLOTS_AVAILABLE, response.status());
    }

    @Test
    void availability_returnsBaseSlotsWhenFreeBusyFails() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        conn.setProvider(CalendarProviderType.GOOGLE);
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE)).thenReturn(Optional.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));
        when(freeBusyService.busyIntervals(userId,
                Instant.parse("2026-05-10T00:00:00Z"),
                Instant.parse("2026-05-11T00:00:00Z")))
                .thenThrow(new CalendarClientException(500, "downstream"));

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(1, response.slots().size());
        assertEquals("a", response.slots().get(0).slotId());
        assertEquals(AvailabilityStatus.AVAILABLE, response.status());
    }

    @Test
    void availability_failedConnectionComputesDegradedSlots() {
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.FAILED);
        conn.setProvider(CalendarProviderType.GOOGLE);
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE)).thenReturn(Optional.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 3L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));
        when(freeBusyService.busyIntervals(userId,
                Instant.parse("2026-05-10T00:00:00Z"),
                Instant.parse("2026-05-11T00:00:00Z")))
                .thenReturn(List.of());

        SlotResponse response = service.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(1, response.slots().size());
        assertEquals(AvailabilityStatus.STALE_CALENDAR_DATA, response.status());
        assertEquals(true, response.degraded());
        assertEquals(3L, response.version());
    }

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
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, userId, eventTypeId))
                .thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
                    public UUID getId() { return bookingId; }
                    public UUID getHostId() { return userId; }
                    public String getStatus() { return "PENDING"; }
                    public Long getVersion() { return 7L; }
                    public Instant getExpiresAt() { return Instant.now().plusSeconds(60); }
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));

        var response = service.cancel("koushik", "30min", bookingId);
        assertEquals("CANCELLED", response.status());
        verify(bookingService).cancelBooking(bookingId, userId, 7L);
    }

    @Test
    void reschedule_rejectsWhenGoogleBusyOverlaps() {
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
                }));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, start, end)).thenReturn(0L);
        when(freeBusyService.busyIntervals(userId, start, end))
                .thenReturn(List.of(new GoogleFreeBusyService.BusyInterval(
                        Instant.parse("2026-05-10T11:10:00Z"),
                        Instant.parse("2026-05-10T11:40:00Z")
                )));

        CustomException ex = assertThrows(CustomException.class,
                () -> service.reschedule("koushik", "30min", bookingId, new PublicRescheduleRequest(start)));
        assertEquals(ErrorCode.SLOT_UNAVAILABLE, ex.getErrorCode());
        verify(bookingService, never()).updateBooking(
                org.mockito.ArgumentMatchers.eq(bookingId),
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(start),
                org.mockito.ArgumentMatchers.eq(end),
                org.mockito.ArgumentMatchers.eq(3L));
    }

    @Test
    void confirm_succeedsOnlyAfterCalendarEventCreation() {
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
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);
        when(freeBusyService.busyIntervals(userId, booking.getStartTime(), booking.getEndTime())).thenReturn(List.of());
        when(fencingTokenGenerator.nextToken()).thenReturn(10L);
        when(calendarEventMappingRepository.claimBookingForSync(bookingId, "google", 10L, "public-confirm"))
                .thenReturn(CalendarEventMappingRepository.ClaimOutcome.CLAIMED);
        when(calendarService.createEvent(new CalendarService.CreateCalendarEventCommand(
                bookingId, "google", "google:" + bookingId)))
                .thenReturn(CalendarService.CreateEventResult.success("ext-1"));
        when(calendarEventMappingRepository.updateMappingWithEventId(bookingId, "google", "ext-1", null, null, 10L, "public-confirm"))
                .thenReturn(CalendarEventMappingRepository.FinalizeOutcome.SUCCESS);

        var response = service.confirm("koushik", "30min", bookingId);

        assertEquals("CONFIRMED", response.status());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    @Test
    void confirm_failsWhenGuestEmailMissingBeforeCalendarCreate() {
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
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);
        when(freeBusyService.busyIntervals(userId, booking.getStartTime(), booking.getEndTime())).thenReturn(List.of());

        CustomException ex = assertThrows(CustomException.class,
                () -> service.confirm("koushik", "30min", bookingId));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
        verify(bookingService, never()).confirmHeldBooking(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirm_failsWhenCalendarEventCreationFails() {
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
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);
        when(freeBusyService.busyIntervals(userId, booking.getStartTime(), booking.getEndTime())).thenReturn(List.of());
        when(fencingTokenGenerator.nextToken()).thenReturn(10L);
        when(calendarEventMappingRepository.claimBookingForSync(bookingId, "google", 10L, "public-confirm"))
                .thenReturn(CalendarEventMappingRepository.ClaimOutcome.CLAIMED);
        when(calendarService.createEvent(new CalendarService.CreateCalendarEventCommand(
                bookingId, "google", "google:" + bookingId)))
                .thenReturn(CalendarService.CreateEventResult.retryable("PROVIDER_DOWN"));

        CustomException ex = assertThrows(CustomException.class, () -> service.confirm("koushik", "30min", bookingId));
        assertEquals(ErrorCode.GOOGLE_EVENT_CREATION_FAILED, ex.getErrorCode());
        verify(bookingService, never()).confirmHeldBooking(bookingId);
    }

    @Test
    void confirm_inProgressMapsToCalendarSyncInProgress() {
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
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);
        when(freeBusyService.busyIntervals(userId, booking.getStartTime(), booking.getEndTime())).thenReturn(List.of());
        when(fencingTokenGenerator.nextToken()).thenReturn(10L);
        when(calendarEventMappingRepository.claimBookingForSync(bookingId, "google", 10L, "public-confirm"))
                .thenReturn(CalendarEventMappingRepository.ClaimOutcome.CLAIMED);
        when(calendarService.createEvent(new CalendarService.CreateCalendarEventCommand(
                bookingId, "google", "google:" + bookingId)))
                .thenReturn(CalendarService.CreateEventResult.retryable("IN_PROGRESS"));
        when(calendarEventMappingRepository.findMappingState(bookingId, "google")).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class, () -> service.confirm("koushik", "30min", bookingId));
        assertEquals(ErrorCode.CALENDAR_SYNC_IN_PROGRESS, ex.getErrorCode());
        verify(bookingService, never()).confirmHeldBooking(bookingId);
    }

    @Test
    void availability_syncingConnectionComputesWhenProviderOptionalEnabled() {
        PublicBookingService providerOptionalService = new PublicBookingService(
                publicBookingTargetResolver,
                slotService,
                bookingService,
                bookingRepository,
                freeBusyService,
                calendarConnectionRepository,
                calendarService,
                calendarEventMappingRepository,
                fencingTokenGenerator,
                timeConversionService,
                true
        );
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");

        when(publicBookingTargetResolver.resolve("koushik", "30min")).thenReturn(target());
        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.SYNCING);
        conn.setProvider(CalendarProviderType.GOOGLE);
        when(calendarConnectionRepository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE)).thenReturn(Optional.of(conn));
        when(slotService.getSlots(org.mockito.ArgumentMatchers.any())).thenReturn(new SlotResponse(
                userId, eventTypeId, LocalDate.of(2026, 5, 10), "UTC", 1L, Instant.now(), false,
                List.of(new SlotDto("a", s1, e1))
        ));

        SlotResponse response = providerOptionalService.availability("koushik", "30min", LocalDate.of(2026, 5, 10));
        assertEquals(AvailabilityStatus.AVAILABLE, response.status());
        assertEquals(1, response.slots().size());
        verify(freeBusyService, never()).busyIntervals(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void availability_missingConnectionComputesDegradedCalendarNotConnectedWhenProviderOptionalEnabled() {
        PublicBookingService providerOptionalService = new PublicBookingService(
                publicBookingTargetResolver,
                slotService,
                bookingService,
                bookingRepository,
                freeBusyService,
                calendarConnectionRepository,
                calendarService,
                calendarEventMappingRepository,
                fencingTokenGenerator,
                timeConversionService,
                true
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
        assertEquals("a", response.slots().get(0).slotId());
        verify(freeBusyService, never()).busyIntervals(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void availability_disconnectedConnectionComputesDegradedCalendarNotConnectedWhenProviderOptionalEnabled() {
        PublicBookingService providerOptionalService = new PublicBookingService(
                publicBookingTargetResolver,
                slotService,
                bookingService,
                bookingRepository,
                freeBusyService,
                calendarConnectionRepository,
                calendarService,
                calendarEventMappingRepository,
                fencingTokenGenerator,
                timeConversionService,
                true
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
        assertEquals("a", response.slots().get(0).slotId());
        verify(freeBusyService, never()).busyIntervals(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirm_succeedsWithoutCalendarWhenProviderOptionalEnabled() {
        PublicBookingService providerOptionalService = new PublicBookingService(
                publicBookingTargetResolver,
                slotService,
                bookingService,
                bookingRepository,
                freeBusyService,
                calendarConnectionRepository,
                calendarService,
                calendarEventMappingRepository,
                fencingTokenGenerator,
                timeConversionService,
                true
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
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);
        when(freeBusyService.busyIntervals(userId, booking.getStartTime(), booking.getEndTime())).thenReturn(List.of());
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                userId,
                CalendarProviderType.GOOGLE,
                CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        var response = providerOptionalService.confirm("koushik", "30min", bookingId);
        assertEquals("CONFIRMED", response.status());
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
        verify(bookingService).confirmHeldBooking(bookingId);
    }

    @Test
    void confirm_optionalModeWithActiveConnection_stillAttemptsCalendarCreate() {
        PublicBookingService providerOptionalService = new PublicBookingService(
                publicBookingTargetResolver,
                slotService,
                bookingService,
                bookingRepository,
                freeBusyService,
                calendarConnectionRepository,
                calendarService,
                calendarEventMappingRepository,
                fencingTokenGenerator,
                timeConversionService,
                true
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
                }));
        when(bookingRepository.findById(new BookingId(bookingId, userId))).thenReturn(Optional.of(booking));
        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.countConflictsExcludingBooking(userId, bookingId, booking.getStartTime(), booking.getEndTime()))
                .thenReturn(0L);
        when(freeBusyService.busyIntervals(userId, booking.getStartTime(), booking.getEndTime())).thenReturn(List.of());
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                userId,
                CalendarProviderType.GOOGLE,
                CalendarConnectionStatus.ACTIVE))
                .thenReturn(Optional.of(conn));
        when(fencingTokenGenerator.nextToken()).thenReturn(10L);
        when(calendarEventMappingRepository.claimBookingForSync(bookingId, "google", 10L, "public-confirm"))
                .thenReturn(CalendarEventMappingRepository.ClaimOutcome.CLAIMED);
        when(calendarService.createEvent(new CalendarService.CreateCalendarEventCommand(
                bookingId, "google", "google:" + bookingId)))
                .thenReturn(CalendarService.CreateEventResult.permanent("INVALID_REQUEST"));

        var response = providerOptionalService.confirm("koushik", "30min", bookingId);
        assertEquals("CONFIRMED", response.status());
        verify(calendarService).createEvent(new CalendarService.CreateCalendarEventCommand(
                bookingId, "google", "google:" + bookingId));
        verify(bookingService).confirmHeldBooking(bookingId);
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
