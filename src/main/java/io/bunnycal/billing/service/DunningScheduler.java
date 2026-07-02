package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.notification.BillingEventPublisher;
import io.bunnycal.billing.notification.BillingNotificationService;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.payments.config.BillingProperties;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failed-payment recovery (dunning). Stripe Smart Retries drive the actual re-charge; we
 * mirror status from webhooks and own the grace window + lockout. When a PAST_DUE
 * subscription's grace window elapses without recovery, it transitions to EXPIRED and the
 * customer is notified.
 *
 * <p>Gated by {@code billing.enabled}; distributed via ShedLock.
 */
@Component
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
public class DunningScheduler {

    private static final Logger log = LoggerFactory.getLogger(DunningScheduler.class);

    private final SubscriptionRepository subscriptionRepository;
    private final BillingEventPublisher billingEventPublisher;
    private final PaymentAuditService auditService;
    private final TimeSource timeSource;
    private final BillingProperties billingProperties;

    public DunningScheduler(SubscriptionRepository subscriptionRepository,
                            BillingEventPublisher billingEventPublisher,
                            PaymentAuditService auditService,
                            TimeSource timeSource,
                            BillingProperties billingProperties) {
        this.subscriptionRepository = subscriptionRepository;
        this.billingEventPublisher = billingEventPublisher;
        this.auditService = auditService;
        this.timeSource = timeSource;
        this.billingProperties = billingProperties;
    }

    @Scheduled(cron = "${billing.dunning.cron:0 30 * * * *}")
    @SchedulerLock(name = "billing_dunning", lockAtMostFor = "PT10M")
    @Transactional
    public void expireLapsedSubscriptions() {
        if (!billingProperties.enabled()) {
            return;
        }
        List<Subscription> lapsed = subscriptionRepository.findByStatusAndGraceUntilBefore(
                SubscriptionStatus.PAST_DUE, timeSource.now());
        for (Subscription sub : lapsed) {
            sub.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(sub);
            auditService.record(PaymentAuditService.ACTOR_SYSTEM, "Subscription", sub.getId(),
                    "DUNNING_EXPIRED",
                    Map.of("status", SubscriptionStatus.PAST_DUE.name()),
                    Map.of("status", SubscriptionStatus.EXPIRED.name()));
            billingEventPublisher.publishForUser(sub.getUserId(), sub.getId(),
                    BillingNotificationService.SUBSCRIPTION_EXPIRED, null);
        }
        if (!lapsed.isEmpty()) {
            log.info("billing.dunning_expired count={}", lapsed.size());
        }
    }
}
