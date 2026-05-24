package com.daedalussystems.easySchedule.sync.worker;

import java.time.Instant;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BookingSyncWorkerScheduler {
    private static final Logger log = LoggerFactory.getLogger(BookingSyncWorkerScheduler.class);
    private static final Duration STUCK_PROCESSING_THRESHOLD = Duration.ofMinutes(5);

    private final BookingSyncWorker worker;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public BookingSyncWorkerScheduler(
            BookingSyncWorker worker,
            @Value("${sync.worker.batch-size:50}") int batchSize,
            @Value("${sync.worker.max-batches-per-run:5}") int maxBatchesPerRun) {
        this.worker = worker;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatchesPerRun = Math.max(1, maxBatchesPerRun);
    }

    @Scheduled(fixedDelayString = "${sync.worker.fixed-delay-ms:2000}")
    @SchedulerLock(
            name = "calendar-sync-worker",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT1S"
    )
    public void run() {
        int reclaimed = worker.reclaimStuckJobs(Instant.now().minus(STUCK_PROCESSING_THRESHOLD));
        if (reclaimed > 0) {
            log.warn("sync_job_stuck_processing_reclaimed reclaimedCount={}", reclaimed);
        }
        for (int i = 0; i < maxBatchesPerRun; i++) {
            int processed = worker.processPending(batchSize);
            if (processed < batchSize) {
                break;
            }
        }
    }
}
