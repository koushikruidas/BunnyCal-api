package com.daedalussystems.easySchedule.booking.dto;

import com.daedalussystems.easySchedule.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicRescheduleRequest(
        Instant startTime
 ) implements ForwardCompatibleRequest {
}
