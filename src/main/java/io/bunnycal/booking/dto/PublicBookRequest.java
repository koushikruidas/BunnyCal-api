package io.bunnycal.booking.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicBookRequest(Instant startTime, String guestEmail, String guestName, String slotToken)
        implements ForwardCompatibleRequest {

    public PublicBookRequest(Instant startTime, String guestEmail, String guestName) {
        this(startTime, guestEmail, guestName, null);
    }
}
