package com.daedalussystems.easySchedule.booking.outbox;

import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_MAX_ATTEMPTS;
import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_PROCESSING_TIMEOUT;

import com.daedalussystems.easySchedule.common.time.TimeSource;
import java.time.Instant;
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
 * {@link com.daedalussystems.easySchedule.booking.contract.BookingContracts#OUTBOX_PROCESSING_TIMEOUT}
 * back to PENDING so the next worker poll can reclaim them.
 *
 * <p>Rows at max attempts are permanently failed rather than reset to avoid an
 * infinite reaper-worker loop.
 */
@Component
public class OutboxReaper {

    private static final Logger log = LoggerFactory.getLogger(OutboxReaper.class);

    private final OutboxEventRepository outboxRepo;
    private final TimeSource timeSource;

    public OutboxReaper(OutboxEventRepository outboxRepo, TimeSource timeSource) {
        this.outboxRepo = outboxRepo;
        this.timeSource = timeSource;
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void recoverStuck() {
        Instant now = timeSource.now();
        Instant cutoff = now.minus(OUTBOX_PROCESSING_TIMEOUT);

        int recovered = outboxRepo.recoverStuck(
                OutboxEventStatus.PENDING,
                OutboxEventStatus.PROCESSING,
                now, cutoff, OUTBOX_MAX_ATTEMPTS);

        int failed = outboxRepo.failExhausted(
                OutboxEventStatus.FAILED,
                OutboxEventStatus.PROCESSING,
                cutoff, OUTBOX_MAX_ATTEMPTS);

        if (recovered > 0 || failed > 0) {
            log.warn("outbox.reaper recovered={} permanentlyFailed={}", recovered, failed);
        }
    }
}
