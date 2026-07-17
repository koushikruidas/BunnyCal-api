package io.bunnycal.availability.dto;

import io.bunnycal.availability.domain.EventAvailabilityMode;
import java.util.List;

/** Atomic replacement of a demand-driven event type's schedule mode and weekly windows. */
public record EventAvailabilityScheduleRequest(
        EventAvailabilityMode mode,
        List<EventAvailabilityWindowRequest> windows
) {}
