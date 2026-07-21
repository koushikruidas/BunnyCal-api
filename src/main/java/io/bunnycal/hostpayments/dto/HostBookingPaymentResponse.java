package io.bunnycal.hostpayments.dto;

import java.time.Instant;
import java.util.UUID;

public record HostBookingPaymentResponse(
        UUID paymentId,
        String provider,
        long amountMinor,
        String currency,
        String status,
        Instant paidAt,
        Instant refundedAt,
        String failureCode,
        boolean requiresAttention
) {
}
