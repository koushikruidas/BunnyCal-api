package com.daedalussystems.easySchedule.booking.outbox;

import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_BATCH_SIZE;
import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_INITIAL_BACKOFF;
import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_MAX_ATTEMPTS;
import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_MAX_BACKOFF;

import com.daedalussystems.easySchedule.common.time.TimeSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the outbox and delivers pending events to the downstream dispatcher.
 *
 * <p><b>Concurrency model</b><br>
 * Multiple instances may run in parallel (different JVM nodes or different
 * scheduled threads). Correctness is guaranteed by the two-phase claim:
 * <ol>
 *   <li><em>Claim TX</em> (REQUIRES_NEW): {@code SELECT … FOR UPDATE SKIP LOCKED}
 *       acquires row-level locks, then a bulk {@code UPDATE status=PROCESSING}
 *       commits. Other workers skip the locked rows entirely.</li>
 *   <li><em>Process TX</em> (REQUIRES_NEW, per event): loads the row, inserts into
 *       {@code processed_events ON CONFLICT DO NOTHING} as the idempotency guard,
 *       dispatches, then marks PROCESSED. A failure rolls back the TX, leaving
 *       the event in PROCESSING for the reaper to reclaim.</li>
 * </ol>
 *
 * <p><b>Why TransactionTemplate instead of @Transactional</b><br>
 * Spring's {@code @Transactional} proxy intercepts calls <em>from outside</em> the
 * bean. Self-calls within the same bean bypass the proxy and inherit the ambient
 * TX, which would merge the claim and process phases into one TX — defeating the
 * SKIP LOCKED guarantee. {@link TransactionTemplate} is used directly to ensure
 * each phase runs in an independent, tightly-scoped transaction.
 */
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

    // ─────────────────────────────────────────────────────────────────
    // Phase 1 — claim
    // ─────────────────────────────────────────────────────────────────

    private List<UUID> claimBatch() {
        List<UUID> ids = requiresNew.execute(status -> {
            Instant now = timeSource.now();
            List<UUID> claimed = outboxRepo.claimPendingIds(now, OUTBOX_BATCH_SIZE);
            if (!claimed.isEmpty()) {
                outboxRepo.markAsProcessing(claimed, OutboxEventStatus.PROCESSING, now);
            }
            return claimed;
        });
        return ids != null ? ids : List.of();
    }

    // ─────────────────────────────────────────────────────────────────
    // Phase 2 — process (one event, one TX)
    // ─────────────────────────────────────────────────────────────────

    private void processOne(UUID id) {
        try {
            requiresNew.execute(status -> {
                OutboxEvent event = outboxRepo.findById(id).orElse(null);
                if (event == null || event.getStatus().isTerminal()) return null;

                // Option-A idempotency guard: insert succeeds (1) on the first
                // dispatch. If the event was already dispatched in a previous
                // attempt (the worker crashed after dispatch but before marking
                // PROCESSED) the insert is a no-op (0) and we skip dispatch.
                int inserted = processedEventRepo.tryInsert(id, timeSource.now());
                if (inserted > 0) {
                    dispatcher.dispatch(event); // throws on failure → TX rolls back
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

    // Records a dispatch failure in a fresh TX so the retry metadata
    // persists even though the process TX was rolled back.
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
                    event.setNextAttemptAt(timeSource.now().plusMillis(computeBackoffMillis(nextAttempt)));
                }
                outboxRepo.save(event);
                return null;
            });
        } catch (Exception ex) {
            log.error("outbox.failure.record.error id={}", id, ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers — package-private for unit testing
    // ─────────────────────────────────────────────────────────────────

    /**
     * Exponential backoff: {@code min(INITIAL * 2^(attemptCount-1), MAX)}.
     * {@code attemptCount} is the count <em>after</em> incrementing on failure
     * (so 1 = first failure, delay = INITIAL).
     */
    static long computeBackoffMillis(int attemptCount) {
        long initial = OUTBOX_INITIAL_BACKOFF.toMillis();
        long max = OUTBOX_MAX_BACKOFF.toMillis();
        int shift = Math.min(attemptCount - 1, 62);
        long powerOfTwo = 1L << shift;
        // Guard: if initial * powerOfTwo would overflow long, return max directly.
        if (powerOfTwo > Long.MAX_VALUE / initial) return max;
        return Math.min(initial * powerOfTwo, max);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
