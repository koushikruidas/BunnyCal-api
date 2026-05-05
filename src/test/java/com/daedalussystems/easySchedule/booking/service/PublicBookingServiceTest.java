package com.daedalussystems.easySchedule.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.dto.SlotDto;
import com.daedalussystems.easySchedule.availability.dto.SlotResponse;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.availability.service.SlotService;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.domain.BookingId;
import com.daedalussystems.easySchedule.booking.dto.PublicRescheduleRequest;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.calendar.service.GoogleFreeBusyService;
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

    @Mock UserRepository userRepository;
    @Mock EventTypeRepository eventTypeRepository;
    @Mock SlotService slotService;
    @Mock BookingService bookingService;
    @Mock BookingRepository bookingRepository;
    @Mock GoogleFreeBusyService freeBusyService;

    private PublicBookingService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID eventTypeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PublicBookingService(
                userRepository,
                eventTypeRepository,
                slotService,
                bookingService,
                bookingRepository,
                freeBusyService
        );
    }

    @Test
    void availability_filtersSlotsOverlappingBusyIntervals() {
        User user = User.builder().id(userId).username("koushik").timezone("UTC").email("a@b.com").name("n").build();
        EventType et = EventType.builder().id(eventTypeId).userId(userId).slug("30min").duration(Duration.ofMinutes(30)).build();
        Instant s1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant e1 = Instant.parse("2026-05-10T10:30:00Z");
        Instant s2 = Instant.parse("2026-05-10T10:30:00Z");
        Instant e2 = Instant.parse("2026-05-10T11:00:00Z");

        when(userRepository.findByUsername("koushik")).thenReturn(Optional.of(user));
        when(eventTypeRepository.findByUserIdAndSlug(userId, "30min")).thenReturn(Optional.of(et));
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
    }

    @Test
    void confirm_rejectsWhenDbConflictExists() {
        UUID bookingId = UUID.randomUUID();
        User user = User.builder().id(userId).username("koushik").timezone("UTC").email("a@b.com").name("n").build();
        EventType et = EventType.builder().id(eventTypeId).userId(userId).slug("30min").duration(Duration.ofMinutes(30)).build();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z")).build();

        when(userRepository.findByUsername("koushik")).thenReturn(Optional.of(user));
        when(eventTypeRepository.findByUserIdAndSlug(userId, "30min")).thenReturn(Optional.of(et));
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
        User user = User.builder().id(userId).username("koushik").timezone("UTC").email("a@b.com").name("n").build();
        EventType et = EventType.builder().id(eventTypeId).userId(userId).slug("30min").duration(Duration.ofMinutes(30)).build();
        Booking booking = Booking.builder().id(bookingId).hostId(userId).eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-10T10:00:00Z")).endTime(Instant.parse("2026-05-10T10:30:00Z")).build();

        when(userRepository.findByUsername("koushik")).thenReturn(Optional.of(user));
        when(eventTypeRepository.findByUserIdAndSlug(userId, "30min")).thenReturn(Optional.of(et));
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
        User user = User.builder().id(userId).username("koushik").timezone("UTC").email("a@b.com").name("n").build();
        EventType et = EventType.builder().id(eventTypeId).userId(userId).slug("30min").duration(Duration.ofMinutes(30)).build();
        Instant start = Instant.parse("2026-05-10T11:00:00Z");
        Instant end = Instant.parse("2026-05-10T11:30:00Z");

        when(userRepository.findByUsername("koushik")).thenReturn(Optional.of(user));
        when(eventTypeRepository.findByUserIdAndSlug(userId, "30min")).thenReturn(Optional.of(et));
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
}
