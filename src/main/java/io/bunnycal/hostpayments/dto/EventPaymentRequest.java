package io.bunnycal.hostpayments.dto;

import java.util.UUID;

public record EventPaymentRequest(Boolean enabled, UUID connectionId, Long amountMinor, String currency) {
}
