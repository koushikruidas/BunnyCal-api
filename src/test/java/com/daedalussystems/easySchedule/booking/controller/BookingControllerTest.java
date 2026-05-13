package com.daedalussystems.easySchedule.booking.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyOutcome;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyService;
import com.daedalussystems.easySchedule.booking.service.BookingService;
import com.daedalussystems.easySchedule.booking.service.MeetingQueryService;
import com.daedalussystems.easySchedule.common.time.TimeConversionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock private BookingService bookingService;
    @Mock private MeetingQueryService meetingQueryService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private TimeConversionService timeConversionService;

    @Test
    void cancel_usesAuthenticatedPrincipalAndCancelsWhenNotAlreadyCancelled() {
        BookingController controller = new BookingController(
                bookingService,
                meetingQueryService,
                idempotencyService,
                new ObjectMapper().findAndRegisterModules(),
                timeConversionService);
        UUID hostId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(hostId, null);

        when(idempotencyService.execute(anyString(), eq(hostId), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Supplier<Object> work = (java.util.function.Supplier<Object>) inv.getArgument(4);
                    return new IdempotencyOutcome.Fresh<>(200, work.get());
                });
        when(bookingService.cancelBookingAsHost(bookingId, hostId, null)).thenReturn(booking(bookingId, hostId));

        controller.cancel(authentication, bookingId, "idem-cancel");

        verify(bookingService).cancelBookingAsHost(bookingId, hostId, null);
    }

    @Test
    void cancel_delegatesToServiceForIdempotentSemantics() {
        BookingController controller = new BookingController(
                bookingService,
                meetingQueryService,
                idempotencyService,
                new ObjectMapper().findAndRegisterModules(),
                timeConversionService);
        UUID hostId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(hostId, null);

        when(idempotencyService.execute(anyString(), eq(hostId), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Supplier<Object> work = (java.util.function.Supplier<Object>) inv.getArgument(4);
                    return new IdempotencyOutcome.Fresh<>(200, work.get());
                });
        when(bookingService.cancelBookingAsHost(bookingId, hostId, null)).thenReturn(booking(bookingId, hostId));

        controller.cancel(authentication, bookingId, "idem-cancel");

        verify(bookingService).cancelBookingAsHost(bookingId, hostId, null);
    }

    @Test
    void cancel_requestHashStableForEquivalentInput() {
        BookingController controller = new BookingController(
                bookingService,
                meetingQueryService,
                idempotencyService,
                new ObjectMapper().findAndRegisterModules(),
                timeConversionService);
        UUID hostId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(hostId, null);

        when(idempotencyService.execute(anyString(), eq(hostId), anyString(), anyString(), any()))
                .thenReturn(new IdempotencyOutcome.Fresh<>(200, Map.of("ok", true)));

        controller.cancel(authentication, bookingId, "idem-1");
        controller.cancel(authentication, bookingId, "idem-2");

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, org.mockito.Mockito.times(2))
                .execute(anyString(), eq(hostId), anyString(), hashCaptor.capture(), any());
        assertEquals(hashCaptor.getAllValues().get(0), hashCaptor.getAllValues().get(1));
    }

    private static Booking booking(UUID bookingId, UUID hostId) {
        Booking booking = new Booking();
        booking.setId(bookingId);
        booking.setHostId(hostId);
        booking.setEventTypeId(UUID.randomUUID());
        booking.setStartTime(Instant.parse("2026-05-12T10:00:00Z"));
        booking.setEndTime(Instant.parse("2026-05-12T10:30:00Z"));
        booking.setCreatedAt(Instant.parse("2026-05-01T10:00:00Z"));
        return booking;
    }
}
