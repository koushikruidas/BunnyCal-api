package io.bunnycal.hostpayments.dto;

import java.util.UUID;

public record EventPaymentResponse(boolean enabled, UUID connectionId, long amountMinor,
                                   String currency, String provider) {
}
