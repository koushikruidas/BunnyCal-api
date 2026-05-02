package com.daedalussystems.easySchedule.booking.outbox;

import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_BATCH_SIZE;
import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_INITIAL_BACKOFF;
import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_MAX_ATTEMPTS;
import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_MAX_BACKOFF;

import com.daedalussystems.easySchedule.common.time.TimeSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxEventRepository outboxRepo;
    private final ProcessedEventRepository processedEventRepo;
    private final OutboxEventDispatcher dispatcher;
    private final TimeSource timeSource;
    private final TransactionTemplate requiresNew;

    public OutboxWorker(
            OutboxEventRepository outboxRepo,
            ProcessedEventRepository processedEventRepo,
            OutboxEventDispatcher dispatcher,
            TimeSource timeSource,
            PlatformTransactionManager txManager) {

        this.outboxRepo = outboxRepo;
        this.processedEventRepo = processedEventRepo;
        this.dispatcher = dispatcher;
        this.timeSource = timeSource;

        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        List<UUID> ids = claimBatch();
        for (UUID id : ids) {
            processOne(id);
        }
    }

    // ✅ FIXED: atomic claim
    private List<UUID> claimBatch() {
        List<UUID> ids = requiresNew.execute(status ->
                outboxRepo.claimBatch(timeSource.now(), OUTBOX_BATCH_SIZE)
        );
        return ids != null ? ids : List.of();
    }

    private void processOne(UUID id) {
        try {
            requiresNew.execute(status -> {
                OutboxEvent event = outboxRepo.findById(id).orElse(null);
                if (event == null || event.getStatus().isTerminal()) return null;

                int inserted = processedEventRepo.tryInsert(id, timeSource.now());

                if (inserted > 0) {
                    dispatcher.dispatch(event);
                }

                event.setStatus(OutboxEventStatus.PROCESSED);
                outboxRepo.save(event);

                log.debug("outbox.processed id={} type={}", id, event.getEventType());
                return null;
            });

        } catch (Exception ex) {
            log.warn("outbox.dispatch.failed id={}", id, ex);
            recordFailure(id, ex);
        }
    }

    private void recordFailure(UUID id, Exception cause) {
        try {
            requiresNew.execute(status -> {
                OutboxEvent event = outboxRepo.findById(id).orElse(null);
                if (event == null || event.getStatus().isTerminal()) return null;

                int nextAttempt = event.getAttemptCount() + 1;
                event.setAttemptCount(nextAttempt);
                event.setLastError(truncate(cause.getMessage(), 500));

                if (nextAttempt >= OUTBOX_MAX_ATTEMPTS) {
                    event.setStatus(OutboxEventStatus.FAILED);
                    log.error("outbox.event.failed.permanently id={} attempts={}", id, nextAttempt);
                } else {
                    event.setStatus(OutboxEventStatus.PENDING);

                    long delay = computeBackoffMillis(nextAttempt);
                    event.setNextAttemptAt(timeSource.now().plusMillis(delay));
                }

                outboxRepo.save(event);
                return null;
            });

        } catch (Exception ex) {
            log.error("outbox.failure.record.error id={}", id, ex);
        }
    }

    /**
     * ✅ FIXED: exponential + jitter
     */
    static long computeBackoffMillis(int attemptCount) {
        long initial = OUTBOX_INITIAL_BACKOFF.toMillis();
        long max = OUTBOX_MAX_BACKOFF.toMillis();

        int shift = Math.min(attemptCount - 1, 62);
        long base = initial << shift;

        long capped = Math.min(base, max);

        // jitter: 0 → 50%
        long jitter = ThreadLocalRandom.current().nextLong(0, capped / 2 + 1);

        return capped + jitter;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}