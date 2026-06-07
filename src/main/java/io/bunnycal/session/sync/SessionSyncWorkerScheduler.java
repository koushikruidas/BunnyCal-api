package io.bunnycal.session.sync;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SessionSyncWorkerScheduler {

    private final SessionSyncWorker worker;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public SessionSyncWorkerScheduler(
            SessionSyncWorker worker,
            @Value("${sync.session-worker.batch-size:${sync.worker.batch-size:50}}") int batchSize,
            @Value("${sync.session-worker.max-batches-per-run:${sync.worker.max-batches-per-run:5}}") int maxBatchesPerRun) {
        this.worker = worker;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatchesPerRun = Math.max(1, maxBatchesPerRun);
    }

    @Scheduled(fixedDelayString = "${sync.session-worker.fixed-delay-ms:${sync.worker.fixed-delay-ms:2000}}")
    @SchedulerLock(
            name = "session-calendar-sync-worker",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT1S"
    )
    public void run() {
        for (int i = 0; i < maxBatchesPerRun; i++) {
            int processed = worker.processPending(batchSize);
            if (processed < batchSize) {
                break;
            }
        }
    }
}
