package io.bunnycal.booking.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateBookingRequest(UUID hostId, UUID eventTypeId, Instant startTime, Instant endTime) implements ForwardCompatibleRequest {
}
