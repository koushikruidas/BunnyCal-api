package io.bunnycal.booking.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record PublicGroupDateCardResponse(
        LocalDate date,
        PublicGroupDateStatus status,
        int sessionCount,
        int bookableSessionCount,
        int totalCapacity,
        int totalBooked,
        LocalTime nextAvailableStartTime,
        List<PublicGroupSessionCardResponse> sessions
) {
}
