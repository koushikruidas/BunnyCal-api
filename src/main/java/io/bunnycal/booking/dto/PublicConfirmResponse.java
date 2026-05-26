package io.bunnycal.booking.dto;

import java.util.UUID;

public record PublicConfirmResponse(
        UUID bookingId,
        String status,
        String manageToken
) {
}
