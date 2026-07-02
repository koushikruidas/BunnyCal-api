package io.bunnycal.admin.revenue.dto;

import java.time.Instant;
import java.util.List;

/**
 * Revenue report for a window. Money in minor units of {@link #currency}.
 *
 * <p>Merchant-of-Record waterfall: <b>gross → fees → refunds → net</b>. Because per-transaction
 * provider fees are not persisted, {@link #feesEstimatedMinor} is an estimate derived from a
 * configured rate ({@code billing.fees.processor-percent-bps}); {@link #feesEstimated} is true
 * when an estimate was applied and false when fees are unavailable (rate not configured), in
 * which case {@link #netEstimatedMinor} excludes fees. Payout reconciliation is not implemented;
 * {@link #payoutsAvailable} is always false for now (UI shows a placeholder).
 */
public record RevenueReportDto(
        Instant from,
        Instant to,
        String currency,
        long grossMinor,
        long taxMinor,
        long discountMinor,
        long refundsMinor,
        long feesEstimatedMinor,
        boolean feesEstimated,
        int feePercentBps,
        long netEstimatedMinor,
        boolean payoutsAvailable,
        long invoiceCount,
        List<PlanRevenueDto> byPlan,
        List<DailyRevenueDto> overTime,
        boolean byCountryAvailable) {

    public record PlanRevenueDto(String planId, String planName, long grossMinor, long invoiceCount) {
    }

    public record DailyRevenueDto(String day, long grossMinor) {
    }
}
