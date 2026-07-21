package io.bunnycal.hostpayments.dto;

import java.util.UUID;

public record PublicPaymentStatusResponse(UUID paymentId, String paymentStatus, String bookingStatus,
                                          boolean confirmed, boolean refunded) {
}
