package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.notification.BillingEventPublisher;
import io.bunnycal.billing.notification.BillingNotificationService;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.config.BillingProperties;
import java.time.Duration;
import java.time.Instant;
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
 * Sends "trial ending" reminders at 7, 3, and 1 day(s) before {@code trialEnd}. Runs once
 * daily; each offset matches a subscription on exactly one calendar day (its trialEnd
 * falling in that day's window), which dedups reminders without extra bookkeeping.
 *
 * <p>Gated by {@code billing.enabled}; distributed via ShedLock so only one pod runs it.
 */
@Component
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
public class TrialReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrialReminderScheduler.class);
    private static final int[] REMINDER_DAYS = {7, 3, 1};

    private final SubscriptionRepository subscriptionRepository;
    private final BillingEventPublisher billingEventPublisher;
    private final TimeSource timeSource;
    private final BillingProperties billingProperties;

    public TrialReminderScheduler(SubscriptionRepository subscriptionRepository,
                                  BillingEventPublisher billingEventPublisher,
                                  TimeSource timeSource,
                                  BillingProperties billingProperties) {
        this.subscriptionRepository = subscriptionRepository;
        this.billingEventPublisher = billingEventPublisher;
        this.timeSource = timeSource;
        this.billingProperties = billingProperties;
    }

    @Scheduled(cron = "${billing.trial-reminder.cron:0 0 9 * * *}")
    @SchedulerLock(name = "billing_trial_reminders", lockAtMostFor = "PT10M")
    @Transactional
    public void sendTrialReminders() {
        if (!billingProperties.enabled()) {
            return;
        }
        Instant now = timeSource.now();
        for (int days : REMINDER_DAYS) {
            // Window = [now + days, now + days + 24h): the trial ends within that day.
            Instant from = now.plus(Duration.ofDays(days));
            Instant to = from.plus(Duration.ofDays(1));
            List<Subscription> due = subscriptionRepository.findByStatusAndTrialEndBetween(
                    SubscriptionStatus.TRIAL, from, to);
            for (Subscription sub : due) {
                billingEventPublisher.publishForUser(sub.getUserId(), sub.getId(),
                        BillingNotificationService.TRIAL_ENDING, Map.of("daysLeft", days));
            }
            if (!due.isEmpty()) {
                log.info("billing.trial_reminders daysLeft={} count={}", days, due.size());
            }
        }
    }
}
