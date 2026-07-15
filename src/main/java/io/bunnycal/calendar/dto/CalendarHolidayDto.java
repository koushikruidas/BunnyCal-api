package io.bunnycal.calendar.dto;

import java.time.LocalDate;

/** One backend-deduplicated public holiday that becomes a whole day off. */
public record CalendarHolidayDto(
        String id,
        String title,
        LocalDate date
) {}
