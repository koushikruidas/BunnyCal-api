package io.bunnycal.booking.outbox;

import static io.bunnycal.booking.contract.BookingContracts.OUTBOX_BATCH_SIZE;
import static io.bunnycal.booking.contract.BookingContracts.OUTBOX_INITIAL_BACKOFF;
import static io.bunnycal.booking.contract.BookingContracts.OUTBOX_MAX_ATTEMPTS;
import static io.bunnycal.booking.contract.BookingContracts.OUTBOX_MAX_BACKOFF;
import static org.apache.commons.lang3.StringUtils.truncate;

import io.bunnycal.common.time.TimeSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
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

    // ── Metrics ──────────────────────────────────────────────────────────────
    private final MeterRegistry meterRegistry;
    private final DistributionSummary outboxLag;
    private final Counter retryCounter;
    private final Counter retryCounterAlias;
    private final Counter bookingFailedTotal;
    private final Timer processingLatency;

    public OutboxWorker(
            OutboxEventRepository outboxRepo,
            ProcessedEventRepository processedEventRepo,
            OutboxEventDispatcher dispatcher,
            TimeSource timeSource,
            PlatformTransactionManager txManager,
            MeterRegistry meterRegistry) {

        this.outboxRepo = outboxRepo;
        this.processedEventRepo = processedEventRepo;
        this.dispatcher = dispatcher;
        this.timeSource = timeSource;

        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.meterRegistry = meterRegistry;

        this.outboxLag = DistributionSummary.builder("outbox.lag.milliseconds")
                .description("Time between outbox event creation and processing, in milliseconds")
                .baseUnit("milliseconds")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.retryCounter = Counter.builder("outbox.retries.total")
                .description("Number of outbox events that entered RETRYING state after a dispatch failure")
                .register(meterRegistry);
        this.retryCounterAlias = Counter.builder("outbox_retry_total")
                .description("Alias counter for outbox retries")
                .register(meterRegistry);

        // Counts booking outbox events that exhausted all retries and entered DLQ (FAILED).
        // Lets operators correlate SLO violations against outright delivery failures.
        this.bookingFailedTotal = Counter.builder("booking.failed.total")
                .description("Booking outbox events that reached DLQ after exhausting max retry attempts")
                .register(meterRegistry);

        this.processingLatency = Timer.builder("outbox.processing.latency.seconds")
                .description("End-to-end time to process one outbox event, including failure recording")
                .publishPercentileHistogram()
                .register(meterRegistry);
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
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            requiresNew.execute(status -> {
                OutboxEvent event = outboxRepo.findById(id).orElse(null);
                if (event == null || event.getStatus().isTerminal()) return null;

                // Single DB clock call — used for both lag recording and tryInsert.
                Instant now = timeSource.now();
                outboxLag.record(Duration.between(event.getCreatedAt(), now).toMillis());

                // Two-guard contract:
                //   Guard 1 — SKIP LOCKED in claimBatch: prevents two live workers
                //             from racing to claim the same row at the same moment.
                //   Guard 2 — processed_events (ON CONFLICT DO NOTHING): the true
                //             safety net. Even after a crash+recovery, if another
                //             worker already committed the processed_events row the
                //             dispatch is skipped here. SKIP LOCKED is an optimisation;
                //             processed_events is the correctness guarantee.
                int inserted = processedEventRepo.tryInsert(id, now);
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
        } finally {
            sample.stop(processingLatency);
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
                    event.setNextAttemptAt(null);
                    log.error("outbox.event.dlq id={} attempts={}", id, nextAttempt);
                    if ("Booking".equals(event.getAggregateType())) {
                        bookingFailedTotal.increment();
                    }
                } else {
                    // RETRYING (not PENDING) — distinguishes "has failed at least once"
                    // from "fresh event". Enables observability queries like
                    // "how many events are in backoff?" without a timestamp scan.
                    event.setStatus(OutboxEventStatus.RETRYING);
                    retryCounter.increment();
                    retryCounterAlias.increment();

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

    static long computeBackoffMillis(int attemptCount) {
        if (attemptCount <= 0) {
            throw new IllegalArgumentException("attemptCount must be >= 1");
        }

        long initial = OUTBOX_INITIAL_BACKOFF.toMillis();
        long max = OUTBOX_MAX_BACKOFF.toMillis();

        long base = initial;

        for (int i = 1; i < attemptCount; i++) {
            if (base >= max / 2) {
                base = max;
                break;
            }
            base *= 2;
        }

        long capped = Math.min(base, max);

        long jitter = ThreadLocalRandom.current().nextLong(0, capped / 2 + 1);

        return capped + jitter;
    }
}
