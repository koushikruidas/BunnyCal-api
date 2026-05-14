package com.daedalussystems.easySchedule.booking.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.domain.BookingId;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingLifecycleServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingService bookingService;
    @Mock private GuestCapabilityTokenService guestCapabilityTokenService;

    private BookingLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new BookingLifecycleService(
                bookingRepository,
                bookingService,
                guestCapabilityTokenService,
                true,
                "");
    }

    @Test
    void cancelAsGuest_requiresTokenWhenEnforced() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, hostId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, hostId, "PENDING", 2L)));
        when(bookingRepository.findById(new BookingId(bookingId, hostId)))
                .thenReturn(Optional.of(booking(bookingId, hostId)));
        when(guestCapabilityTokenService.allows(bookingId, hostId, null, BookingActionType.CANCEL)).thenReturn(false);
        when(guestCapabilityTokenService.allows(bookingId, hostId, null, BookingActionType.MANAGE_BOOKING)).thenReturn(false);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.cancelAsGuest(bookingId, hostId, eventTypeId, null));
        org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(bookingService, never()).cancelBooking(bookingId, hostId, 2L, CancellationSource.GUEST, null);
    }

    @Test
    void cancelAsGuest_withValidTokenDelegatesCancellation() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        when(bookingRepository.findStateByIdAndHostAndEventType(bookingId, hostId, eventTypeId))
                .thenReturn(Optional.of(stateRow(bookingId, hostId, "PENDING", 2L)));
        when(bookingRepository.findById(new BookingId(bookingId, hostId)))
                .thenReturn(Optional.of(booking(bookingId, hostId)));
        when(guestCapabilityTokenService.allows(bookingId, hostId, "tok", BookingActionType.CANCEL)).thenReturn(true);

        service.cancelAsGuest(bookingId, hostId, eventTypeId, "tok");

        verify(bookingService).cancelBooking(bookingId, hostId, 2L, CancellationSource.GUEST, null);
    }

    private static Booking booking(UUID bookingId, UUID hostId) {
        Booking booking = new Booking();
        booking.setId(bookingId);
        booking.setHostId(hostId);
        booking.setEventTypeId(UUID.randomUUID());
        booking.setStartTime(Instant.parse("2026-06-01T10:00:00Z"));
        booking.setEndTime(Instant.parse("2026-06-01T10:30:00Z"));
        booking.setCreatedAt(Instant.parse("2026-05-01T10:00:00Z"));
        return booking;
    }

    private static BookingRepository.BookingStateRow stateRow(UUID id, UUID hostId, String status, long version) {
        return new BookingRepository.BookingStateRow() {
            @Override public UUID getId() { return id; }
            @Override public UUID getHostId() { return hostId; }
            @Override public String getStatus() { return status; }
            @Override public Long getVersion() { return version; }
            @Override public Instant getExpiresAt() { return null; }
                    public Long getTerminalIntentEpoch() { return 0L; }
        };
    }
}
