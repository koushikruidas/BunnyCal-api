package io.bunnycal.booking.outbox;

import static io.bunnycal.booking.contract.BookingContracts.OUTBOX_MAX_ATTEMPTS;
import static io.bunnycal.booking.contract.BookingContracts.OUTBOX_PROCESSING_TIMEOUT;

import io.bunnycal.common.time.TimeSource;
import java.time.Instant;

import io.bunnycal.booking.contract.BookingContracts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crash-recovery sweep for orphaned PROCESSING rows.
 *
 * <p>A worker that dies between the claim TX and the process TX leaves its
 * claimed rows in PROCESSING indefinitely. This reaper runs periodically and
 * resets rows that have been PROCESSING longer than
 * {@link BookingContracts#OUTBOX_PROCESSING_TIMEOUT}
 * back to PENDING so the next worker poll can reclaim them.
 *
 * <p>Rows at max attempts are permanently failed rather than reset to avoid an
 * infinite reaper-worker loop.
 */
@Component
public class OutboxReaper {

    private static final Logger log = LoggerFactory.getLogger(OutboxReaper.class);

    private final OutboxEventRepository outboxRepo;
    private final ProcessedEventRepository processedEventRepo;
    private final TimeSource timeSource;

    public OutboxReaper(OutboxEventRepository outboxRepo,
                        ProcessedEventRepository processedEventRepo,
                        TimeSource timeSource) {
        this.outboxRepo = outboxRepo;
        this.processedEventRepo = processedEventRepo;
        this.timeSource = timeSource;
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void recoverStuck() {
        Instant now = timeSource.now();
        Instant cutoff = now.minus(OUTBOX_PROCESSING_TIMEOUT);

        // Release the idempotency claims of the rows we are about to recover, BEFORE resetting
        // their status — the query selects on status = PROCESSING, so it must see them unchanged.
        // A worker that died between committing its claim and finishing the dispatch left a guard
        // row for a send that never happened; without clearing it, the reclaimed event would find
        // the guard, skip the dispatch, and the notification would be lost silently.
        int releasedClaims = processedEventRepo.releaseStuckClaims(cutoff, OUTBOX_MAX_ATTEMPTS);

        int recovered = outboxRepo.recoverStuck(
                OutboxEventStatus.PENDING,
                OutboxEventStatus.PROCESSING,
                now, cutoff, OUTBOX_MAX_ATTEMPTS);

        int failed = outboxRepo.failExhausted(
                OutboxEventStatus.FAILED,
                OutboxEventStatus.PROCESSING,
                cutoff, OUTBOX_MAX_ATTEMPTS);

        if (recovered > 0 || failed > 0) {
            log.warn("outbox.reaper recovered={} permanentlyFailed={} releasedClaims={}",
                    recovered, failed, releasedClaims);
        }
    }
}
