package io.bunnycal.booking.dto;

import java.time.LocalDate;
import java.util.List;

public record PublicGroupSessionsResponse(
        String timezone,
        LocalDate startDate,
        int days,
        PublicGroupSeriesSummaryResponse seriesSummary,
        List<PublicGroupDateCardResponse> dates
) {
}
