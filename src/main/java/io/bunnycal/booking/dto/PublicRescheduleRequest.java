package io.bunnycal.booking.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicRescheduleRequest(
        Instant startTime,
        /**
         * Signed proof that the requested slot was offered for this event's current participant
         * roster. Required for COLLECTIVE, where moving the booking has to be re-checked against
         * every host; ignored for the single-host kinds, which validate the assigned user directly.
         */
        String slotToken
 ) implements ForwardCompatibleRequest {

    /** Single-host reschedule: no roster to prove, so no slot token. */
    public PublicRescheduleRequest(Instant startTime) {
        this(startTime, null);
    }
}
