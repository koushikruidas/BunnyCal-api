package io.bunnycal.admin.operations;

import io.bunnycal.admin.operations.dto.OperationsSummaryDto;
import io.bunnycal.billing.domain.PaymentTransactionStatus;
import io.bunnycal.billing.domain.RefundStatus;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.CouponRepository;
import io.bunnycal.billing.repository.PaymentTransactionRepository;
import io.bunnycal.billing.repository.PromoCodeRepository;
import io.bunnycal.billing.repository.RefundRepository;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.booking.outbox.OutboxEventRepository;
import io.bunnycal.booking.outbox.OutboxEventStatus;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.webhook.WebhookEventRepository;
import io.bunnycal.payments.webhook.WebhookEventStatus;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily action-needed summary over existing operational queues and billing state.
 * This module is intentionally aggregate-only; detailed queue browsers land in later phases.
 */
@Service
public class OperationsService {

    static final int FAILED_PAYMENTS_WINDOW_DAYS = 30;

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final RefundRepository refundRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final CouponRepository couponRepository;
    private final PromoCodeRepository promoCodeRepository;
    private final CalendarSyncJobRepository calendarSyncJobRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TimeSource timeSource;

    public OperationsService(PaymentTransactionRepository paymentTransactionRepository,
                             WebhookEventRepository webhookEventRepository,
                             RefundRepository refundRepository,
                             SubscriptionRepository subscriptionRepository,
                             CouponRepository couponRepository,
                             PromoCodeRepository promoCodeRepository,
                             CalendarSyncJobRepository calendarSyncJobRepository,
                             OutboxEventRepository outboxEventRepository,
                             TimeSource timeSource) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.refundRepository = refundRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.couponRepository = couponRepository;
        this.promoCodeRepository = promoCodeRepository;
        this.calendarSyncJobRepository = calendarSyncJobRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.timeSource = timeSource;
    }

    @Transactional(readOnly = true)
    public OperationsSummaryDto summary() {
        Instant now = timeSource.now();
        Instant failedPaymentsSince = now.minus(FAILED_PAYMENTS_WINDOW_DAYS, ChronoUnit.DAYS);

        long failedPayments = paymentTransactionRepository.countByStatusAndOccurredAtGreaterThanEqual(
                PaymentTransactionStatus.FAILED, failedPaymentsSince);
        long failedWebhooks = webhookEventRepository.countByStatus(WebhookEventStatus.FAILED);
        long pendingRefunds = refundRepository.countByStatus(RefundStatus.PENDING);
        long pastDueSubscriptions = subscriptionRepository.countByStatus(SubscriptionStatus.PAST_DUE);

        long couponIssues = couponRepository.countActionNeeded(now);
        long promoCodeIssues = promoCodeRepository.countActionNeeded(now);

        long syncDeadLetters = calendarSyncJobRepository.countDeadLetters();
        long outboxRetrying = outboxEventRepository.countByStatus(OutboxEventStatus.RETRYING);
        long outboxFailed = outboxEventRepository.countByStatus(OutboxEventStatus.FAILED);

        return new OperationsSummaryDto(
                now,
                FAILED_PAYMENTS_WINDOW_DAYS,
                failedPayments,
                failedWebhooks,
                pendingRefunds,
                pastDueSubscriptions,
                new OperationsSummaryDto.PromoIssues(
                        couponIssues + promoCodeIssues,
                        couponIssues,
                        promoCodeIssues),
                new OperationsSummaryDto.JobsNeedingAttention(
                        syncDeadLetters + outboxRetrying + outboxFailed,
                        syncDeadLetters,
                        outboxRetrying,
                        outboxFailed));
    }
}
