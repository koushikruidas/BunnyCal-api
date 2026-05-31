package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.bunnycal.availability.dto.AvailabilityRuleRequest;
import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.draft.dto.DraftCreateRequest;
import io.bunnycal.booking.draft.service.DraftOrganizerService;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.calendar.service.CalendarService;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = {
        "booking.confirm.async-calendar.enabled=true",
        "booking.confirm.shadow-compare.enabled=false"
})
class PublicConfirmAsyncCalendarIT extends AbstractBookingIT {

    @Autowired
    private DraftOrganizerService draftOrganizerService;

    @Autowired
    private PublicBookingService publicBookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @MockitoBean
    private CalendarService calendarService;

    @Test
    void confirm_asyncMode_returnsSyncingAndDoesNotCallGoogleSynchronously() {
        DraftCreateRequest request = new DraftCreateRequest(
                "host.async@example.com",
                "Async Host",
                "UTC",
                "Async Confirm",
                "No sync provider calls on confirm",
                "Google Meet",
                30,
                30,
                10,
                List.of(new AvailabilityRuleRequest(DayOfWeek.WEDNESDAY, LocalTime.MIN, LocalTime.of(23, 59))),
                List.of()
        );
        DraftOrganizerService.DraftCreated draftCreated = draftOrganizerService.create(request);

        Instant start = LocalDate.now().plusDays(7).atTime(10, 0).toInstant(ZoneOffset.UTC);
        var hold = publicBookingService.hold("d", draftCreated.draft().slug(), new PublicBookRequest(
                start,
                "guest.async@example.com",
                "Guest Async"
        ));

        UUID bookingId = hold.bookingId();
        var response = publicBookingService.confirm("d", draftCreated.draft().slug(), bookingId);

        assertEquals("SYNCING", response.status());
        String dbStatus = bookingRepository.findStateById(bookingId)
                .orElseThrow()
                .getStatus();
        assertEquals("CONFIRMED", dbStatus);
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
    }
}
