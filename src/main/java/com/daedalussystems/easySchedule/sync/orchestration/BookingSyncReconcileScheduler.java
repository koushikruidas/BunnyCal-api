package com.daedalussystems.easySchedule.sync.orchestration;

import java.util.concurrent.ThreadLocalRandom;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BookingSyncReconcileScheduler {

    private final BookingSyncReconciler reconciler;
    private final int batchSize;
    private final long jitterMs;

    public BookingSyncReconcileScheduler(
            BookingSyncReconciler reconciler,
            @Value("${sync.reconcile.batch-size:100}") int batchSize,
            @Value("${sync.reconcile.jitter-ms:750}") long jitterMs) {
        this.reconciler = reconciler;
        this.batchSize = batchSize;
        this.jitterMs = Math.max(0L, jitterMs);
    }

    @Scheduled(fixedDelayString = "${sync.reconcile.fixed-delay-ms:30000}")
    @SchedulerLock(
            name = "calendar-sync-reconcile-observed-state",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S"
    )
    public void run() {
        applyJitter();
        reconciler.reconcile(batchSize);
    }

    private void applyJitter() {
        if (jitterMs <= 0L) {
            return;
        }
        long delay = ThreadLocalRandom.current().nextLong(jitterMs + 1L);
        if (delay == 0L) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
