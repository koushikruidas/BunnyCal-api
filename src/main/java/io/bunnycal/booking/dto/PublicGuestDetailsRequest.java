package io.bunnycal.booking.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The guest identity for a booking that was already held.
 *
 * <p>The public page reserves the slot as soon as the guest picks a time, before it knows who
 * they are, so name and email arrive on this separate call rather than on the hold. Confirm
 * reads these fields off the booking row and takes no body of its own, so this is what puts
 * them there.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicGuestDetailsRequest(
        String guestEmail,
        String guestName,
        String notes
) implements ForwardCompatibleRequest {
}
