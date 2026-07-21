package io.bunnycal.hostpayments.dto;

import java.time.Instant;
import java.util.UUID;

public record PaymentInitializationResponse(
        UUID paymentId, String provider, String providerAccountId, String publishableKey,
        String clientSecret, long amountMinor, String currency, String status, Instant expiresAt) {
}
