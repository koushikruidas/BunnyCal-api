package io.bunnycal.booking.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

public record PublicGroupSeriesSummaryResponse(
        String label,
        String scheduleText,
        DayOfWeek weekday,
        LocalDate startDate,
        LocalDate throughDate,
        LocalTime firstSessionStartTime,
        LocalTime lastSessionEndTime,
        int sessionCountPerOccurrence
) {
}
