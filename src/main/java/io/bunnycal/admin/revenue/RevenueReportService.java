package io.bunnycal.admin.revenue;

import io.bunnycal.admin.revenue.dto.RevenueReportDto;
import io.bunnycal.admin.revenue.dto.RevenueReportDto.DailyRevenueDto;
import io.bunnycal.admin.revenue.dto.RevenueReportDto.PlanRevenueDto;
import io.bunnycal.billing.repository.RefundRepository;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository.PlanRevenueRow;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository.RevenueTotals;
import io.bunnycal.billing.service.PlanService;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.config.BillingProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the admin Revenue report from invoice/refund aggregates over a window. Honest about
 * what's real vs. estimated vs. unavailable:
 * <ul>
 *   <li><b>Gross / tax / discount / refunds / by-plan / over-time</b> — computed from stored data.</li>
 *   <li><b>Fees</b> — estimated from {@code billing.fees.processor-percent-bps} (per-transaction
 *       fees aren't persisted). Marked as an estimate; if the rate is 0/unset, fees are unavailable.</li>
 *   <li><b>Net</b> — gross − fees − refunds (fees omitted when unavailable).</li>
 *   <li><b>Payouts</b> — not implemented (placeholder flag).</li>
 *   <li><b>By country</b> — no country is stored on invoices, so this is unavailable.</li>
 * </ul>
 */
@Service
public class RevenueReportService {

    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final SubscriptionInvoiceRepository invoiceRepository;
    private final RefundRepository refundRepository;
    private final PlanService planService;
    private final BillingProperties billingProperties;
    private final TimeSource timeSource;

    public RevenueReportService(SubscriptionInvoiceRepository invoiceRepository,
                                RefundRepository refundRepository,
                                PlanService planService,
                                BillingProperties billingProperties,
                                TimeSource timeSource) {
        this.invoiceRepository = invoiceRepository;
        this.refundRepository = refundRepository;
        this.planService = planService;
        this.billingProperties = billingProperties;
        this.timeSource = timeSource;
    }

    @Transactional(readOnly = true)
    public RevenueReportDto report(Instant from, Instant to) {
        Instant end = to != null ? to : timeSource.now();
        Instant start = from != null ? from : end.minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS);

        RevenueTotals totals = invoiceRepository.revenueTotals(start, end);
        long gross = totals.getGrossMinor();
        long tax = totals.getTaxMinor();
        long discount = totals.getDiscountMinor();
        long invoiceCount = totals.getInvoiceCount();
        long refunds = refundRepository.sumSucceededMinorBetween(start, end);

        int feeBps = Math.max(0, billingProperties.fees().processorPercentBps());
        boolean feesEstimated = feeBps > 0;
        long fees = feesEstimated ? Math.round(gross * (feeBps / 10000.0)) : 0;
        long net = feesEstimated ? (gross - fees - refunds) : (gross - refunds);

        List<PlanRevenueDto> byPlan = invoiceRepository.revenueByPlan(start, end).stream()
                .map((PlanRevenueRow r) -> new PlanRevenueDto(
                        r.getPlanId().toString(), r.getPlanName(), r.getGrossMinor(), r.getInvoiceCount()))
                .toList();

        List<DailyRevenueDto> overTime = invoiceRepository.revenueByDay(start, end).stream()
                .map(r -> new DailyRevenueDto(r.getDay(), r.getGrossMinor()))
                .toList();

        return new RevenueReportDto(
                start, end, resolveCurrency(),
                gross, tax, discount, refunds,
                fees, feesEstimated, feeBps,
                net,
                false,            // payoutsAvailable — reconciliation not implemented
                invoiceCount,
                byPlan, overTime,
                false);           // byCountryAvailable — no country stored
    }

    private String resolveCurrency() {
        List<String> byVolume = invoiceRepository.currenciesByVolume(PageRequest.of(0, 1));
        if (!byVolume.isEmpty()) {
            return byVolume.get(0);
        }
        try {
            return planService.requireDefaultPlan().getCurrency();
        } catch (RuntimeException e) {
            return "USD";
        }
    }
}
