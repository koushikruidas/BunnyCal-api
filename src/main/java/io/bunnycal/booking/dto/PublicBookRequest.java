package io.bunnycal.booking.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import io.bunnycal.form.dto.AnswerInput;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicBookRequest(
        Instant startTime,
        String guestEmail,
        String guestName,
        String notes,
        String slotToken,
        List<AnswerInput> answers,
        String embedToken,
        /**
         * The invitee's IANA zone id, filled in by the controller from the X-Timezone header
         * rather than by the client body, so it cannot be spoofed independently of the header the
         * rest of the request is normalised against. Null when the caller sent no header; the
         * booking then stores none and its mail falls back to the host's zone.
         */
        String guestTimezone
) implements ForwardCompatibleRequest {

    public PublicBookRequest(Instant startTime, String guestEmail, String guestName) {
        this(startTime, guestEmail, guestName, null, null, null, null, null);
    }

    public PublicBookRequest(Instant startTime, String guestEmail, String guestName, String slotToken) {
        this(startTime, guestEmail, guestName, null, slotToken, null, null, null);
    }

    /** The shape carried before the invitee's timezone was recorded. */
    public PublicBookRequest(Instant startTime,
                             String guestEmail,
                             String guestName,
                             String notes,
                             String slotToken,
                             List<AnswerInput> answers,
                             String embedToken) {
        this(startTime, guestEmail, guestName, notes, slotToken, answers, embedToken, null);
    }
}
