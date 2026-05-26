package io.bunnycal.booking.ownership;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.common.exception.CustomException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingOwnershipServiceTest {

    @Mock
    private BookingOwnershipRepository repository;

    private BookingOwnershipService service;

    @BeforeEach
    void setUp() {
        service = new BookingOwnershipService(repository, new SimpleMeterRegistry());
    }

    @Test
    void ownershipMaterialization_allowedBeforeBookings() {
        UUID bookingId = UUID.randomUUID();
        EventType eventType = EventType.builder()
                .projectionProvider(CalendarProviderType.GOOGLE)
                .projectionConnectionId(UUID.randomUUID())
                .projectionCalendarId("primary")
                .build();
        when(repository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> service.ensureOwnership(bookingId, eventType));
    }

    @Test
    void ownershipMaterialization_rejectedWhenProjectionDriftsAfterMaterialized() {
        UUID bookingId = UUID.randomUUID();
        EventType eventType = EventType.builder()
                .projectionProvider(CalendarProviderType.MICROSOFT)
                .projectionConnectionId(UUID.randomUUID())
                .projectionCalendarId("cal-m")
                .build();
        BookingOwnership existing = new BookingOwnership();
        existing.setBookingId(bookingId);
        existing.setProjectionProvider(CalendarProviderType.GOOGLE);
        existing.setProjectionConnectionId(UUID.randomUUID());
        existing.setProjectionCalendarId("primary");
        when(repository.findByBookingId(bookingId)).thenReturn(Optional.of(existing));

        assertThrows(CustomException.class, () -> service.ensureOwnership(bookingId, eventType));
    }
}
