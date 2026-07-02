package io.bunnycal.billing.dto;

import io.bunnycal.billing.domain.InvoiceStatus;
import io.bunnycal.billing.domain.SubscriptionInvoice;
import java.time.Instant;
import java.util.UUID;

/** Billing-history row for the UI. */
public record InvoiceDto(
        UUID id,
        String invoiceNumber,
        InvoiceStatus status,
        long totalMinor,
        long amountRefundedMinor,
        String currency,
        Instant periodStart,
        Instant periodEnd,
        Instant issuedAt) {

    public static InvoiceDto from(SubscriptionInvoice i) {
        return new InvoiceDto(
                i.getId(),
                i.getInvoiceNumber(),
                i.getStatus(),
                i.getTotalMinor(),
                i.getAmountRefundedMinor(),
                i.getCurrency(),
                i.getPeriodStart(),
                i.getPeriodEnd(),
                i.getIssuedAt());
    }
}
