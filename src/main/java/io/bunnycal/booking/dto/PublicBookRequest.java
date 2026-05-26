package io.bunnycal.booking.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicBookRequest(Instant startTime, String guestEmail, String guestName) implements ForwardCompatibleRequest {
}
