package io.bunnycal.admin.dashboard;

import io.bunnycal.admin.dashboard.dto.DashboardMetricsDto;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.PaymentTransactionStatus;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.PaymentTransactionRepository;
import io.bunnycal.billing.repository.RefundRepository;
import io.bunnycal.billing.repository.SubscriptionInvoiceRepository;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.billing.service.PlanService;
import io.bunnycal.common.enums.UserStatus;
import io.bunnycal.common.time.TimeSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles admin dashboard metrics from aggregate repository queries. Read-only; computes
 * point-in-time counts and 30-day windows. Paid/free/trial user buckets are derived from
 * live subscription state so they reconcile with the entitlement model.
 */
@Service
public class DashboardMetricsService {

    private static final int WINDOW_DAYS = 30;

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final RefundRepository refundRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PlanService planService;
    private final TimeSource timeSource;

    public DashboardMetricsService(UserRepository userRepository,
                                   SubscriptionRepository subscriptionRepository,
                                   SubscriptionInvoiceRepository invoiceRepository,
                                   RefundRepository refundRepository,
                                   PaymentTransactionRepository transactionRepository,
                                   PlanService planService,
                                   TimeSource timeSource) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.refundRepository = refundRepository;
        this.transactionRepository = transactionRepository;
        this.planService = planService;
        this.timeSource = timeSource;
    }

    @Transactional(readOnly = true)
    public DashboardMetricsDto metrics() {
        Instant since = timeSource.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);

        long totalUsers = userRepository.count();
        long activeUsers = userRepository.countByStatus(UserStatus.ACTIVE);

        long paidUsers = subscriptionRepository.countByStatus(SubscriptionStatus.ACTIVE);
        long trialUsers = subscriptionRepository.countByStatus(SubscriptionStatus.TRIAL);
        long pastDueUsers = subscriptionRepository.countByStatus(SubscriptionStatus.PAST_DUE);
        // Free = users without a live paid/trial subscription.
        long withLiveSub = subscriptionRepository.countDistinctUsersWithLiveSubscription();
        long freeUsers = Math.max(0, totalUsers - withLiveSub);

        long mrr = subscriptionRepository.sumMonthlyRecurringRevenueMinor();
        long revenue = invoiceRepository.sumCollectedMinorSince(since);
        long refunds = refundRepository.sumSucceededMinorSince(since);
        long refundsCount = refundRepository.countByCreatedAtGreaterThanEqual(since);
        long failedPayments = transactionRepository.countByStatusAndOccurredAtGreaterThanEqual(
                PaymentTransactionStatus.FAILED, since);
        long newUsers = userRepository.countByCreatedAtGreaterThanEqual(since);

        // Conversion = paying users / total users (guard divide-by-zero).
        double conversion = totalUsers == 0 ? 0.0 : (double) paidUsers / (double) totalUsers;

        String currency = resolveCurrency();

        return new DashboardMetricsDto(
                totalUsers,
                activeUsers,
                freeUsers,
                trialUsers,
                paidUsers,
                pastDueUsers,
                mrr,
                revenue,
                refunds,
                failedPayments,
                refundsCount,
                newUsers,
                conversion,
                currency);
    }

    private String resolveCurrency() {
        try {
            return planService.requireDefaultPlan().getCurrency();
        } catch (RuntimeException e) {
            return "USD";
        }
    }
}
