package io.bunnycal.admin.operations.dto;

import java.time.Instant;

/**
 * Daily operations landing summary: one place to see what needs attention now.
 * Counts are read-only aggregates over existing systems; where detail screens are not yet
 * built, the frontend surfaces that honestly instead of inventing actions.
 */
public record OperationsSummaryDto(
        Instant generatedAt,
        int failedPaymentsWindowDays,
        long failedPayments,
        long failedWebhooks,
        long pendingRefunds,
        long pastDueSubscriptions,
        PromoIssues promoIssues,
        JobsNeedingAttention jobsNeedingAttention) {

    public record PromoIssues(
            long total,
            long activeCouponsExpiredOrExhausted,
            long activePromoCodesBrokenOrExpired) {
    }

    public record JobsNeedingAttention(
            long total,
            long syncDeadLetters,
            long outboxRetrying,
            long outboxFailed) {
    }
}
