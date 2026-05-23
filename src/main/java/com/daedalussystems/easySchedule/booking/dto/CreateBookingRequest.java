package com.daedalussystems.easySchedule.booking.dto;

import com.daedalussystems.easySchedule.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateBookingRequest(UUID hostId, UUID eventTypeId, Instant startTime, Instant endTime) implements ForwardCompatibleRequest {
}
