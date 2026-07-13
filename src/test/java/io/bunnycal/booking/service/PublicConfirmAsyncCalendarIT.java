package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
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
    private PublicBookingService publicBookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EventTypeRepository eventTypeRepository;

    @MockitoBean
    private CalendarService calendarService;

    @Test
    void confirm_asyncMode_returnsSyncingAndDoesNotCallGoogleSynchronously() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User host = userRepository.save(User.builder()
                .email("host-async-" + suffix + "@example.com")
                .name("Async Host")
                .username("async-" + suffix)
                .timezone("UTC")
                .status(UserStatus.ACTIVE)
                .build());

        String slug = "async-confirm-" + suffix;
        EventType eventType = eventTypeRepository.save(EventType.builder()
                .userId(host.getId())
                .name("Async Confirm")
                .description("No sync provider calls on confirm")
                .location("Phone")
                .conferencingProvider(ConferencingProviderType.NONE)
                .slug(slug)
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(10))
                .kind(EventKind.ONE_ON_ONE)
                .capacity(1)
                .published(true)
                .build());

        LocalDate bookingDate = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.WEDNESDAY));
        jdbc.update("""
                INSERT INTO availability_rules
                    (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                VALUES (?, ?, 'WEDNESDAY', ?, ?, NOW(), NOW())
                """, UUID.randomUUID(), host.getId(), LocalTime.MIN, LocalTime.of(23, 59));

        Instant start = bookingDate.atTime(10, 0).toInstant(ZoneOffset.UTC);
        var hold = publicBookingService.hold(host.getUsername(), eventType.getSlug(), new PublicBookRequest(
                start,
                "guest.async@example.com",
                "Guest Async"
        ));

        UUID bookingId = hold.bookingId();
        var response = publicBookingService.confirm(host.getUsername(), eventType.getSlug(), bookingId);

        assertEquals("SYNCING", response.status());
        String dbStatus = bookingRepository.findStateById(bookingId)
                .orElseThrow()
                .getStatus();
        assertEquals("CONFIRMED", dbStatus);
        verify(calendarService, never()).createEvent(org.mockito.ArgumentMatchers.any());
    }
}
