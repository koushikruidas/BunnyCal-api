package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.notification.BillingEventPublisher;
import io.bunnycal.billing.notification.BillingNotificationService;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.audit.PaymentAuditService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the application-side {@code TRIAL -> EXPIRED} transition.
 *
 * <p>{@code subscriptions.trial_end} is the immutable deadline captured when the trial starts.
 * Entitlement checks continue to use that deadline defensively, while this service makes the
 * persisted status converge to the same truth. It is called both on subscription access and by
 * the scheduled sweep, so correctness does not depend on scheduler timing.
 */
@Service
@RequiredArgsConstructor
public class TrialLifecycleService {

    private static final String ENTITY = "Subscription";

    private final SubscriptionRepository subscriptionRepository;
    private final TimeSource timeSource;
    private final PaymentAuditService auditService;
    private final BillingEventPublisher billingEventPublisher;

    /**
     * Expires one elapsed trial. Returns true only when this call performed the transition.
     */
    @Transactional
    public boolean expireIfElapsed(Subscription subscription) {
        if (subscription == null || subscription.getStatus() != SubscriptionStatus.TRIAL) {
            return false;
        }
        Instant trialEnd = subscription.getTrialEnd();
        if (trialEnd == null || timeSource.now().isBefore(trialEnd)) {
            return false;
        }

        subscription.setStatus(SubscriptionStatus.EXPIRED);
        Subscription saved = subscriptionRepository.saveAndFlush(subscription);
        auditService.record(PaymentAuditService.ACTOR_SYSTEM, ENTITY, saved.getId(),
                "TRIAL_EXPIRED",
                Map.of("status", SubscriptionStatus.TRIAL.name(), "trialEnd", trialEnd.toString()),
                Map.of("status", SubscriptionStatus.EXPIRED.name(), "trialEnd", trialEnd.toString()));
        billingEventPublisher.publishForUser(saved.getUserId(), saved.getId(),
                BillingNotificationService.SUBSCRIPTION_EXPIRED, null);
        return true;
    }

    /** Sweeps every elapsed trial using the same transition used by the lazy access path. */
    @Transactional
    public int expireElapsedTrials() {
        Instant now = timeSource.now();
        List<Subscription> elapsed = subscriptionRepository
                .findByStatusAndTrialEndLessThanEqual(SubscriptionStatus.TRIAL, now);
        int transitioned = 0;
        for (Subscription subscription : elapsed) {
            if (expireIfElapsed(subscription)) {
                transitioned++;
            }
        }
        return transitioned;
    }
}
