package io.bunnycal.booking.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import io.bunnycal.form.dto.AnswerInput;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The guest identity for a booking that was already held.
 *
 * <p>The public page reserves the slot as soon as the guest picks a time, before it knows who
 * they are, so name and email arrive on this separate call rather than on the hold. Confirm
 * reads these fields off the booking row and takes no body of its own, so this is what puts
 * them there.
 *
 * <p>{@code answers} and {@code embedToken} exist for the same reason. The embed collects the
 * host's custom questions on the details step, and used to send them with the hold — which only
 * worked while the hold happened after that step. Once the widget reserves the slot on selection
 * like the public page does, the hold call is made before any answer exists, so they travel here
 * instead. Both stay optional: the hosted page sends neither.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicGuestDetailsRequest(
        String guestEmail,
        String guestName,
        String notes,
        List<AnswerInput> answers,
        String embedToken
) implements ForwardCompatibleRequest {

    /** The hosted page's shape — no embed answers. */
    public PublicGuestDetailsRequest(String guestEmail, String guestName, String notes) {
        this(guestEmail, guestName, notes, null, null);
    }
}
