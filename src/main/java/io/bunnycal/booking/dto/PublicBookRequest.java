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
        String embedToken
) implements ForwardCompatibleRequest {

    public PublicBookRequest(Instant startTime, String guestEmail, String guestName) {
        this(startTime, guestEmail, guestName, null, null, null, null);
    }

    public PublicBookRequest(Instant startTime, String guestEmail, String guestName, String slotToken) {
        this(startTime, guestEmail, guestName, null, slotToken, null, null);
    }
}
