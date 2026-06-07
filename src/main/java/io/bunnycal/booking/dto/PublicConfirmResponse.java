package io.bunnycal.booking.dto;

import java.util.UUID;

public record PublicConfirmResponse(
        UUID bookingId,
        String status,
        String manageToken,
        UUID sessionId   // non-null for GROUP registrations; null for ONE_ON_ONE
) {
    public static PublicConfirmResponse oneOnOne(UUID bookingId, String status, String manageToken) {
        return new PublicConfirmResponse(bookingId, status, manageToken, null);
    }
}
