package io.bunnycal.availability.dto;

import io.bunnycal.availability.domain.EventAvailabilityMode;
import java.util.List;

public record EventAvailabilityScheduleResponse(
        EventAvailabilityMode mode,
        List<EventAvailabilityWindowResponse> windows
) {}
