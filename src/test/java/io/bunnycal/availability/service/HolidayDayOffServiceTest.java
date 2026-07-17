package io.bunnycal.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.repository.AvailabilityOverrideRepository;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.repository.CalendarEventRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HolidayDayOffServiceTest {

    @Mock CalendarEventRepository calendarEventRepository;
    @Mock AvailabilityOverrideRepository availabilityOverrideRepository;

    private HolidayDayOffService service;
    private final UUID userId = UUID.randomUUID();
    private final ZoneId zoneId = ZoneId.of("Asia/Kolkata");

    @BeforeEach
    void setUp() {
        service = new HolidayDayOffService(calendarEventRepository, availabilityOverrideRepository);
    }

    @Test
    void onlyAllDayEventsBecomeDaysOffAndNeighbouringDuplicatesCollapse() {
        when(calendarEventRepository.findHolidayEvents(any(), any(), any())).thenReturn(List.of(
                event("Rath Yatra", "2026-07-15T18:30:00Z", "2026-07-16T18:30:00Z"),
                event("Rath Yatra", "2026-07-16T18:30:00Z", "2026-07-17T18:30:00Z"),
                event("Holiday lunch", "2026-07-18T06:30:00Z", "2026-07-18T07:30:00Z")));

        assertThat(service.holidays(userId, LocalDate.parse("2026-07-16"),
                LocalDate.parse("2026-07-19"), zoneId))
                .containsExactly(new HolidayDeduplicator.Holiday("Rath Yatra", LocalDate.parse("2026-07-16")));
    }

    @Test
    void explicitUserOverrideWinsAtConfirmTime() {
        LocalDate date = LocalDate.parse("2026-08-15");
        when(availabilityOverrideRepository.existsByUserIdAndDate(userId, date)).thenReturn(true);

        assertThat(service.isDayOffUnlessOverridden(userId, date, zoneId)).isFalse();
    }

    private CalendarEvent event(String title, String start, String end) {
        CalendarEvent event = new CalendarEvent();
        event.setTitle(title);
        event.setStartsAt(Instant.parse(start));
        event.setEndsAt(Instant.parse(end));
        return event;
    }
}
