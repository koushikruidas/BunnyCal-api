package io.bunnycal.admin.dashboard.dto;

/**
 * Snapshot of admin dashboard metrics. Money fields are integer minor units in the platform's
 * primary currency. "Window" metrics (revenue, failed payments, refunds) are over the last
 * 30 days; counts are point-in-time.
 */
public record DashboardMetricsDto(
        long totalUsers,
        long activeUsers,
        long freeUsers,
        long trialUsers,
        long paidUsers,
        long pastDueUsers,
        long mrrMinor,
        long revenueLast30dMinor,
        long refundsLast30dMinor,
        long failedPaymentsLast30d,
        long refundsCountLast30d,
        long newUsersLast30d,
        double conversionRate,
        String currency) {
}
