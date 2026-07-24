package io.bunnycal.billing.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically persists elapsed application-owned trials as {@code EXPIRED}. */
@Component
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
public class TrialExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrialExpirationScheduler.class);

    private final TrialLifecycleService trialLifecycleService;

    public TrialExpirationScheduler(TrialLifecycleService trialLifecycleService) {
        this.trialLifecycleService = trialLifecycleService;
    }

    @Scheduled(cron = "${billing.trial-expiration.cron:0 * * * * *}")
    @SchedulerLock(name = "billing_trial_expiration", lockAtMostFor = "PT50S")
    public void expireElapsedTrials() {
        int count = trialLifecycleService.expireElapsedTrials();
        if (count > 0) {
            log.info("billing.trials_expired count={}", count);
        }
    }
}
