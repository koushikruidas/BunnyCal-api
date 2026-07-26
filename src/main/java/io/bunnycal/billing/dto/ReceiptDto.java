package io.bunnycal.billing.dto;

import io.bunnycal.billing.domain.InvoiceStatus;
import java.time.Instant;
import java.util.UUID;

/** Customer-facing payment history row for a BunnyCal-generated receipt. */
public record ReceiptDto(
        UUID id,
        String receiptNumber,
        InvoiceStatus status,
        long totalMinor,
        long amountRefundedMinor,
        String currency,
        Instant periodStart,
        Instant periodEnd,
        Instant issuedAt,
        String paymentId,
        String subscription,
        Instant nextRenewal,
        String officialInvoiceNumber,
        String officialInvoiceUrl) {
}
