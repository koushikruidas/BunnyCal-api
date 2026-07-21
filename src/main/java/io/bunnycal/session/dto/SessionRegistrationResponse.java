package io.bunnycal.session.dto;

import io.bunnycal.booking.dto.QuestionnaireResponse;
import io.bunnycal.hostpayments.dto.HostBookingPaymentResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SessionRegistrationResponse(
        UUID registrationId,
        UUID sessionId,
        UUID hostId,
        String guestEmail,
        String guestName,
        String notes,
        String status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        long version,
        boolean expired,
        List<QuestionnaireResponse> questionnaireResponses,
        HostBookingPaymentResponse payment
) {
}
