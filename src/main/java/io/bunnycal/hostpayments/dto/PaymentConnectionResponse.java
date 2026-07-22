package io.bunnycal.hostpayments.dto;

import java.util.UUID;

public record PaymentConnectionResponse(
        UUID id, String provider, String status, boolean chargesEnabled,
        boolean payoutsEnabled, boolean detailsSubmitted, String restrictionReason) {
}
