package io.bunnycal.booking.ownership;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.service.BookingSchedulingProjectionResolver;
import io.bunnycal.calendar.domain.CalendarProviderType;
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
    @Mock
    private BookingSchedulingProjectionResolver projectionResolver;

    private BookingOwnershipService service;

    @BeforeEach
    void setUp() {
        service = new BookingOwnershipService(repository, new SimpleMeterRegistry(), projectionResolver);
    }

    @Test
    void ownershipMaterialization_allowedBeforeBookings() {
        UUID bookingId = UUID.randomUUID();
        EventType eventType = EventType.builder()
                .build();
        Booking booking = Booking.builder().id(bookingId).build();
        when(repository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(projectionResolver.resolve(booking, eventType)).thenReturn(
                new BookingSchedulingProjectionResolver.SchedulingProjection(
                        CalendarProviderType.GOOGLE, UUID.randomUUID(), "primary"));

        assertDoesNotThrow(() -> service.ensureOwnership(booking, eventType));
    }

    // An event type's calendar can be changed after bookings exist. ensureOwnership runs on every
    // outbox dispatch, so a cancel or reschedule of an ALREADY-WRITTEN booking must keep pointing at
    // the calendar the event actually lives on — re-deriving it from the (now-different) event type
    // used to throw INVALID_STATE_TRANSITION and would have broken every pre-existing booking the
    // moment a host edited their calendar.
    @Test
    void existingOwnership_survivesAnEventTypeWhoseProjectionHasSinceChanged() {
        UUID bookingId = UUID.randomUUID();
        EventType eventType = EventType.builder()
                .build();
        Booking booking = Booking.builder().id(bookingId).build();
        BookingOwnership existing = new BookingOwnership();
        existing.setBookingId(bookingId);
        existing.setProjectionProvider(CalendarProviderType.GOOGLE);
        existing.setProjectionConnectionId(UUID.randomUUID());
        existing.setProjectionCalendarId("primary");
        when(repository.findByBookingId(bookingId)).thenReturn(Optional.of(existing));

        BookingOwnership result = service.ensureOwnership(booking, eventType);

        // The booking keeps the calendar it was written to, not the event type's new one.
        assertEquals(existing, result);
        assertEquals(CalendarProviderType.GOOGLE, result.getProjectionProvider());
        assertEquals("primary", result.getProjectionCalendarId());
        // The event type's projection must not even be consulted once ownership exists.
        verify(projectionResolver, never()).resolve(any(), any());
        verify(repository, never()).save(any());
    }
}
