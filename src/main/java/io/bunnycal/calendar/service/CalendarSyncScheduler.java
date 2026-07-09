package io.bunnycal.calendar.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.OAuthError;
import io.bunnycal.calendar.client.OAuthErrorCategory;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CalendarSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(CalendarSyncScheduler.class);

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventIngestionService ingestionService;
    private final CalendarSyncClientRegistry syncClientRegistry;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarConnectionWriteService connectionWriteService;
    private final ProviderConcurrencyGate concurrencyGate;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate txTemplate;
    private final Timer sweepTimer;
    private final Timer perConnectionTimer;
    private final Counter deadlineHitCounter;
    private final int batchSize;
    private final int maxConnectionsPerTick;

    // Phase 4 (calendar.sync.parallel-enabled): parallel sweep configuration.
    private final boolean parallelEnabled;
    private final int parallelism;
    private final long providerAcquireTimeoutMs;
    private final Duration sweepMaxRuntime;
    private final ExecutorService sweepExecutor;

    // Phase 4 (calendar.sync.webhook-fresh-gating-enabled): when enabled, connections with a
    // fresh (non-expired) webhook channel poll only on the backstop cadence rather than every
    // tick. See CalendarConnectionRepository.findDueForSyncBatchGated.
    private final boolean webhookFreshGatingEnabled;
    private final Duration webhookFreshBackstop;
    // Last sweep's count of due connections eligible but not processed (deadline hit or
    // gate-deferred). Published as a gauge so operators can see the sweep falling behind.
    private final AtomicLong lastUnprocessedDue = new AtomicLong(0);

    /**
     * Convenience constructor defaulting the parallel path OFF (sequential behavior). Retained
     * for existing callers/tests that predate the parallel-sweep parameters.
     */
    public CalendarSyncScheduler(CalendarConnectionRepository connectionRepository,
                                 CalendarEventIngestionService ingestionService,
                                 CalendarSyncClientRegistry syncClientRegistry,
                                 SlotCacheVersionService slotCacheVersionService,
                                 CalendarConnectionWriteService connectionWriteService,
                                 ProviderConcurrencyGate concurrencyGate,
                                 PlatformTransactionManager transactionManager,
                                 MeterRegistry meterRegistry,
                                 int batchSize,
                                 int maxConnectionsPerTick) {
        this(connectionRepository, ingestionService, syncClientRegistry, slotCacheVersionService,
                connectionWriteService, concurrencyGate, transactionManager, meterRegistry,
                batchSize, maxConnectionsPerTick,
                false, 8, 150L, Duration.ofSeconds(25),
                false, Duration.ofMinutes(15));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CalendarSyncScheduler(CalendarConnectionRepository connectionRepository,
                                 CalendarEventIngestionService ingestionService,
                                 CalendarSyncClientRegistry syncClientRegistry,
                                 SlotCacheVersionService slotCacheVersionService,
                                 CalendarConnectionWriteService connectionWriteService,
                                 ProviderConcurrencyGate concurrencyGate,
                                 PlatformTransactionManager transactionManager,
                                 MeterRegistry meterRegistry,
                                 @Value("${calendar.sync.batch-size:100}") int batchSize,
                                 @Value("${calendar.sync.max-connections-per-tick:2000}") int maxConnectionsPerTick,
                                 @Value("${calendar.sync.parallel-enabled:false}") boolean parallelEnabled,
                                 @Value("${calendar.sync.parallelism:8}") int parallelism,
                                 @Value("${calendar.sync.provider-acquire-timeout-ms:150}") long providerAcquireTimeoutMs,
                                 @Value("${calendar.sync.sweep-max-runtime:PT25S}") Duration sweepMaxRuntime,
                                 @Value("${calendar.sync.webhook-fresh-gating-enabled:false}") boolean webhookFreshGatingEnabled,
                                 @Value("${calendar.sync.webhook-fresh-backstop:PT15M}") Duration webhookFreshBackstop) {
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
        this.syncClientRegistry = syncClientRegistry;
        this.slotCacheVersionService = slotCacheVersionService;
        this.connectionWriteService = connectionWriteService;
        this.concurrencyGate = concurrencyGate;
        this.meterRegistry = meterRegistry;
        this.batchSize = Math.max(1, batchSize);
        this.maxConnectionsPerTick = Math.max(this.batchSize, maxConnectionsPerTick);
        this.parallelEnabled = parallelEnabled;
        this.parallelism = Math.max(1, parallelism);
        this.providerAcquireTimeoutMs = Math.max(0L, providerAcquireTimeoutMs);
        // Guard against a misconfigured non-positive runtime that would make every sweep an
        // immediate no-op; fall back to the documented default.
        this.sweepMaxRuntime = (sweepMaxRuntime == null || sweepMaxRuntime.isNegative() || sweepMaxRuntime.isZero())
                ? Duration.ofSeconds(25)
                : sweepMaxRuntime;
        this.webhookFreshGatingEnabled = webhookFreshGatingEnabled;
        this.webhookFreshBackstop = (webhookFreshBackstop == null || webhookFreshBackstop.isNegative())
                ? Duration.ofMinutes(15)
                : webhookFreshBackstop;
        // Bounded worker pool for the parallel path. Fixed `parallelism` threads with a queue
        // holding at most one page of work at a time — runParallel submits a page (≤ batchSize)
        // then joins it fully before fetching the next, so the queue depth is bounded by
        // batchSize and never grows across pages. The pool is shut down explicitly in
        // @PreDestroy. Threads are daemon so a JVM shutdown is never blocked by an idle pool.
        this.sweepExecutor = new ThreadPoolExecutor(
                this.parallelism, this.parallelism,
                60L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                namedThreadFactory("calendar-sync-worker-"));
        // Per-connection tx boundary. Outer sync() loop is non-transactional so a slow
        // provider call on connection N never holds a DB connection across the entire
        // candidate list. REQUIRES_NEW because the scheduler runs outside any tx and we
        // want each connection's progress committed independently.
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.sweepTimer = Timer.builder("calendar.sync.sweep.duration")
                .description("Wall-clock duration of one CalendarSyncScheduler.sync() tick.")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.perConnectionTimer = Timer.builder("calendar.sync.per_connection.duration")
                .description("Wall-clock duration of one connection's sync within a sweep.")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.deadlineHitCounter = Counter.builder("calendar.sync.sweep.deadline_hit.total")
                .description("Sweeps that stopped submitting work because the sweep-max-runtime deadline was reached.")
                .register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("calendar.sync.sweep.unprocessed_due", lastUnprocessedDue, AtomicLong::get)
                .description("Due connections eligible but not processed in the last sweep (deadline hit or gate-deferred).")
                .register(meterRegistry);
    }

    private static java.util.concurrent.ThreadFactory namedThreadFactory(String prefix) {
        AtomicLong seq = new AtomicLong(0);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdownExecutor() {
        sweepExecutor.shutdown();
        try {
            if (!sweepExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                sweepExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            sweepExecutor.shutdownNow();
        }
    }

    /**
     * NO-OVERLAP INVARIANT (read before changing this method).
     *
     * <p>The same {@link CalendarConnection} must never be processed by two workers at once —
     * the due-query ({@code findDueForSyncBatch}) is a plain SELECT with no row-level claim
     * (no FOR UPDATE / SKIP LOCKED / claim column), so nothing at the row level prevents
     * double-processing. Safety rests entirely on sweeps not overlapping:
     * <ul>
     *   <li>Same node: Spring's {@code @Scheduled(fixedDelay)} runs this task on a single
     *       thread and only starts the next tick after this method RETURNS. The parallel
     *       fan-out below lives inside one invocation and is fully joined before we return,
     *       so no worker outlives the sweep.</li>
     *   <li>Across replicas: {@code @SchedulerLock} holds the lock for at most {@code PT2M}.
     *       If a sweep ran longer than that, ShedLock would release and a second replica
     *       could start an overlapping sweep. The hard {@code sweep-max-runtime} deadline
     *       (default PT25S, strictly &lt; lockAtMostFor) guarantees we return well inside the
     *       lock window, so overlap cannot happen.</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${calendar.sync.fixed-delay-ms:30000}")
    @SchedulerLock(
            name = "calendar-sync-sweep",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S"
    )
    public void sync() {
        long sweepStart = System.nanoTime();
        Instant now = Instant.now();
        try {
            if (parallelEnabled) {
                runParallel(now);
            } else {
                runSequential(now);
            }
        } finally {
            sweepTimer.record(System.nanoTime() - sweepStart, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Fetch one page of due connections, choosing the webhook-aware gated query when
     * {@code webhook-fresh-gating-enabled} is set (fresh-webhook connections only appear once
     * their backstop has elapsed), otherwise the original every-tick due-query. Both queries
     * share the same deterministic ordering so pagination is stable either way.
     */
    private List<CalendarConnection> fetchDuePage(Instant now, int pageIndex, int pageLimit) {
        if (webhookFreshGatingEnabled) {
            Instant backstopThreshold = now.minus(webhookFreshBackstop);
            return connectionRepository.findDueForSyncBatchGated(
                    now, backstopThreshold, PageRequest.of(pageIndex, pageLimit));
        }
        return connectionRepository.findDueForSyncBatch(now, PageRequest.of(pageIndex, pageLimit));
    }

    /** Original single-threaded sweep. Unchanged behavior; runs when parallel is disabled. */
    private void runSequential(Instant now) {
        // Phase 3 batching: paginate the due-query so we never load the entire candidate
        // set into memory. Ordered deterministically (NULLS FIRST nextRetryAt, then id) so
        // pagination is stable across calls and across ticks.
        int processed = 0;
        int deferred = 0;
        int pageIndex = 0;
        while (processed < maxConnectionsPerTick) {
            int remaining = maxConnectionsPerTick - processed;
            int pageLimit = Math.min(batchSize, remaining);
            List<CalendarConnection> page = fetchDuePage(now, pageIndex, pageLimit);
            if (page.isEmpty()) {
                break;
            }
            for (CalendarConnection connection : page) {
                CalendarProviderType provider = connection.getProvider();
                if (!concurrencyGate.tryAcquire(provider)) {
                    // Provider saturated this tick — leave the row's next_retry_at unchanged
                    // so the next sweep picks it back up. We do NOT consume the page slot;
                    // the connection is simply skipped this tick.
                    deferred++;
                    continue;
                }
                long perConnectionStart = System.nanoTime();
                try {
                    txTemplate.executeWithoutResult(status -> syncOne(connection));
                } catch (RuntimeException ex) {
                    // Per-connection tx already rolled back; markFailure was attempted inside.
                    // Don't let one bad connection abort the whole sweep.
                    log.warn("calendar_sync_scheduler_connection_uncaught connectionId={} userId={}",
                            connection.getId(), connection.getUserId(), ex);
                } finally {
                    concurrencyGate.release(provider);
                    perConnectionTimer.record(System.nanoTime() - perConnectionStart, TimeUnit.NANOSECONDS);
                }
                processed++;
            }
            // Page was smaller than requested → no further rows exist for this snapshot of `now`.
            if (page.size() < pageLimit) {
                break;
            }
            pageIndex++;
            // Safety: if every row in the page got deferred (provider saturated), break so we
            // don't spin re-reading the same page when nothing has been advanced.
            if (processed == 0 && deferred > 0 && pageIndex > 1) {
                break;
            }
        }
        lastUnprocessedDue.set(deferred);
        log.info("calendar_sync_scheduler_start mode=sequential processed={} deferred={} pages={} batchSize={}",
                processed, deferred, pageIndex + 1, batchSize);
    }

    /**
     * Parallel sweep: fan connections out across the worker pool, bounded by a hard deadline
     * and the per-provider {@link ProviderConcurrencyGate}. See the NO-OVERLAP INVARIANT on
     * {@link #sync()} — all submitted work is joined before this method returns.
     */
    private void runParallel(Instant now) {
        Instant deadline = now.plus(sweepMaxRuntime);
        int processed = 0;
        int deferred = 0;
        int pageIndex = 0;
        boolean deadlineHit = false;

        while (processed < maxConnectionsPerTick) {
            if (!Instant.now().isBefore(deadline)) {
                deadlineHit = true;
                break;
            }
            int remaining = maxConnectionsPerTick - processed;
            int pageLimit = Math.min(batchSize, remaining);
            List<CalendarConnection> page = fetchDuePage(now, pageIndex, pageLimit);
            if (page.isEmpty()) {
                break;
            }

            List<Future<Boolean>> futures = new ArrayList<>(page.size());
            for (CalendarConnection connection : page) {
                if (!Instant.now().isBefore(deadline)) {
                    deadlineHit = true;
                    break;
                }
                futures.add(sweepExecutor.submit(() -> processConnection(connection)));
            }

            // Join this page's work before advancing pagination, so the due-query's stable
            // ordered prefix stays consistent and no worker outlives the sweep.
            for (Future<Boolean> future : futures) {
                Boolean processedOne = awaitConnection(future, deadline);
                if (processedOne == null) {
                    // Timed out / interrupted waiting — count as unprocessed, leave eligible.
                    deferred++;
                } else if (processedOne) {
                    processed++;
                } else {
                    deferred++;
                }
            }

            if (deadlineHit) {
                break;
            }
            if (page.size() < pageLimit) {
                break;
            }
            pageIndex++;
        }

        if (deadlineHit) {
            deadlineHitCounter.increment();
        }
        lastUnprocessedDue.set(deferred);
        long elapsedMs = Duration.between(now, Instant.now()).toMillis();
        if (deadlineHit || deferred > 0) {
            log.warn("calendar_sync_scheduler_start mode=parallel processed={} unprocessedDue={} pages={} parallelism={} deadlineHit={} elapsedMs={}",
                    processed, deferred, pageIndex + 1, parallelism, deadlineHit, elapsedMs);
        } else {
            log.info("calendar_sync_scheduler_start mode=parallel processed={} unprocessedDue={} pages={} parallelism={} deadlineHit=false elapsedMs={}",
                    processed, deferred, pageIndex + 1, parallelism, elapsedMs);
        }
    }

    /**
     * Process one connection on a worker thread. Returns {@code true} if a permit was acquired
     * and the connection was synced (success or handled failure), {@code false} if the provider
     * gate deferred it — in which case it is left eligible for the next sweep.
     */
    private boolean processConnection(CalendarConnection connection) {
        CalendarProviderType provider = connection.getProvider();
        if (!concurrencyGate.tryAcquire(provider, providerAcquireTimeoutMs)) {
            // Provider saturated within the short acquire window — do NOT block the worker;
            // defer this connection to the next sweep (still eligible via the due-query). This
            // is what keeps a saturated provider from starving the other provider's work.
            return false;
        }
        long perConnectionStart = System.nanoTime();
        try {
            txTemplate.executeWithoutResult(status -> syncOne(connection));
        } catch (RuntimeException ex) {
            // Per-connection tx already rolled back; markFailure was attempted inside.
            // Don't let one bad connection abort the whole sweep.
            log.warn("calendar_sync_scheduler_connection_uncaught connectionId={} userId={}",
                    connection.getId(), connection.getUserId(), ex);
        } finally {
            concurrencyGate.release(provider);
            perConnectionTimer.record(System.nanoTime() - perConnectionStart, TimeUnit.NANOSECONDS);
        }
        return true;
    }

    /**
     * Await one submitted connection task, bounded by the sweep deadline (plus a small grace so
     * an in-flight provider call already past the deadline can still commit rather than being
     * abandoned mid-write). Returns the task result, or {@code null} if the wait timed out or
     * was interrupted.
     */
    private Boolean awaitConnection(Future<Boolean> future, Instant deadline) {
        long graceMillis = 2000L;
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis() + graceMillis;
        try {
            return future.get(Math.max(0L, remainingMillis), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            // The task is still running past the drain budget. Do not cancel with interrupt —
            // that could abort a provider write mid-flight; let it finish and commit its own
            // REQUIRES_NEW tx. It simply isn't counted as processed this sweep.
            future.cancel(false);
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException ex) {
            // processConnection swallows per-connection RuntimeExceptions, so reaching here is
            // unexpected; treat as processed-with-error so we don't loop on it.
            log.warn("calendar_sync_scheduler_task_failed", ex.getCause());
            return Boolean.TRUE;
        }
    }

    private void syncOne(CalendarConnection connection) {
        long connectionStart = System.nanoTime();
        CalendarConnectionStatus previousStatus = connection.getStatus();
        String expectedCursor = connection.getProviderSyncCursor();
        ExternalCalendarSyncClient syncClient = syncClientRegistry.clientFor(connection);
        String providerTag = providerTag(connection);
        // Log name is provider-neutral; the actual provider is on the `provider` tag.
        // Historical name was `microsoft_incremental_sync_*` which lied for Google
        // connections (audit fix #5).
        log.debug("calendar_incremental_sync_start connectionId={} userId={} provider={} hasCursor={}",
                connection.getId(), connection.getUserId(), providerTag, expectedCursor != null);
        try {
                ExternalCalendarSyncClient.SyncBatch batch =
                        syncClient.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);
                if (batch.events().isEmpty() && !batch.fullResyncWindow()) {
                    log.debug("calendar_incremental_sync_batch_received connectionId={} provider={} eventCount=0 isFullResync=false nextCursorPresent={}",
                            connection.getId(), providerTag, batch.nextCursor() != null);
                } else {
                    log.info("calendar_incremental_sync_batch_received connectionId={} provider={} eventCount={} isFullResync={} nextCursorPresent={}",
                            connection.getId(), providerTag, batch.events().size(), batch.fullResyncWindow(), batch.nextCursor() != null);
                }
                ingestionService.upsertEvents(connection.getId(), batch.events(), SyncSourceAttribution.PULL_SYNC);
                if (batch.events().isEmpty()) {
                    meterRegistry.counter("calendar.sync.provider_drift_detected.total", "provider", providerTag, "source", "PULL_SYNC")
                            .increment();
                }
                if (batch.nextCursor() != null) {
                    boolean advanced = connectionWriteService.advanceProviderCursor(
                            connection.getId(), expectedCursor, batch.nextCursor(), Instant.now(), "scheduler_incremental_cursor_advance");
                    if (!advanced) {
                        log.info("calendar_sync_cursor_conflict connectionId={} provider={} source=PULL_SYNC",
                                connection.getId(), connection.getProvider());
                    }
                }
                if (previousStatus != CalendarConnectionStatus.ACTIVE) {
                    slotCacheVersionService.bumpVersionAfterCommit(connection.getUserId());
                }
                connectionWriteService.markActive(
                        connection.getId(),
                        connection.getLastTokenExpiresAt(),
                        connection.getLastSyncedAt(),
                        "scheduler_incremental_success");
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectionStart);
                if (previousStatus != CalendarConnectionStatus.ACTIVE || !batch.events().isEmpty()) {
                    log.info("calendar_sync_scheduler_transition connectionId={} userId={} prevStatus={} nextStatus=ACTIVE mode=incremental elapsedMs={}",
                            connection.getId(), connection.getUserId(), previousStatus, elapsedMs);
                } else {
                    log.debug("calendar_sync_scheduler_transition connectionId={} userId={} prevStatus={} nextStatus=ACTIVE mode=incremental elapsedMs={}",
                            connection.getId(), connection.getUserId(), previousStatus, elapsedMs);
                }
            } catch (ExternalCalendarSyncClient.SyncTokenInvalidException invalid) {
                connectionWriteService.invalidateProviderCursor(connection.getId(), Instant.now(), "scheduler_sync_cursor_invalidated");
                ExternalCalendarSyncClient.SyncBatch fullBatch =
                        syncClient.fetchFull(connection, SyncSourceAttribution.PULL_SYNC);
                ingestionService.upsertEvents(connection.getId(), fullBatch.events(), SyncSourceAttribution.PULL_SYNC);
                if (fullBatch.nextCursor() != null) {
                    connectionWriteService.advanceProviderCursor(
                            connection.getId(), null, fullBatch.nextCursor(), Instant.now(), "scheduler_full_cursor_advance");
                }
                slotCacheVersionService.bumpVersionAfterCommit(connection.getUserId());
                connectionWriteService.markActive(
                        connection.getId(),
                        connection.getLastTokenExpiresAt(),
                        connection.getLastSyncedAt(),
                        "scheduler_full_resync_success");
                log.info("calendar_sync_scheduler_transition connectionId={} userId={} prevStatus={} nextStatus=ACTIVE mode=full_resync elapsedMs={}",
                        connection.getId(), connection.getUserId(), previousStatus,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectionStart));
            } catch (RuntimeException ex) {
                handleSyncFailure(connection, previousStatus, providerTag, ex, connectionStart);
            }
    }

    /**
     * F1 + F3: status-aware failure handling.
     *
     * TokenRefresher writes REVOKED/ERROR in its own REQUIRES_NEW transaction before
     * re-throwing. The scheduler must not clobber that classification. When the latest
     * persisted status is already terminal/classified, skip the write. Otherwise stamp a
     * FAILED with the typed OAuth category (F6) so backoff state is updated correctly.
     */
    private void handleSyncFailure(CalendarConnection connection,
                                   CalendarConnectionStatus previousStatus,
                                   String providerTag,
                                   RuntimeException ex,
                                   long connectionStart) {
        CalendarConnection latest = connectionRepository.findById(connection.getId()).orElse(null);
        CalendarConnectionStatus latestStatus = latest == null ? previousStatus : latest.getStatus();
        String latestErrorCode = latest == null ? null : latest.getLastErrorCode();

        if (isTerminalOrAlreadyClassified(latestStatus)) {
            meterRegistry.counter("calendar.sync.terminal_preserved.total",
                            "provider", providerTag,
                            "status", latestStatus.name(),
                            "errorCode", safeTag(latestErrorCode))
                    .increment();
            log.warn("calendar_sync_scheduler_terminal_preserved connectionId={} userId={} prevStatus={} preservedStatus={} preservedErrorCode={} sweepErrorClass={} sweepErrorMessage={} elapsedMs={}",
                    connection.getId(), connection.getUserId(), previousStatus, latestStatus, latestErrorCode,
                    ex.getClass().getSimpleName(), safeMessage(ex),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectionStart));
            return;
        }

        OAuthErrorCategory category = categoryOf(ex);
        String errorCode = errorCodeOf(ex);
        connectionWriteService.markFailureWithCategory(
                connection.getId(),
                category,
                errorCode,
                Instant.now(),
                "scheduler_sync_failure");
        log.warn("calendar_sync_scheduler_transition connectionId={} userId={} prevStatus={} category={} errorCode={} elapsedMs={}",
                connection.getId(), connection.getUserId(), previousStatus, category, errorCode,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectionStart), ex);
    }

    private static boolean isTerminalOrAlreadyClassified(CalendarConnectionStatus status) {
        return status == CalendarConnectionStatus.REVOKED || status == CalendarConnectionStatus.ERROR;
    }

    private static OAuthErrorCategory categoryOf(RuntimeException ex) {
        if (ex instanceof CalendarClientException cce) {
            OAuthError err = cce.getOAuthError();
            if (err != null) {
                return err.category();
            }
            int status = cce.getStatusCode();
            if (status == 401 || status == 403) return OAuthErrorCategory.TERMINAL;
            if (status == 429 || status >= 500) return OAuthErrorCategory.TRANSIENT;
            return OAuthErrorCategory.UNKNOWN;
        }
        return OAuthErrorCategory.UNKNOWN;
    }

    private static String errorCodeOf(RuntimeException ex) {
        if (ex instanceof CalendarClientException cce && cce.getOAuthError() != null) {
            return cce.getOAuthError().stableCode();
        }
        String message = ex.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("invalid_grant")) return "invalid_grant";
            if (normalized.contains("invalid_token")) return "invalid_token";
            if (normalized.contains("unauthorized")) return "unauthorized";
        }
        return "SYNC_FAILED";
    }

    private static String providerTag(CalendarConnection connection) {
        return connection.getProvider() == null ? "unknown" : connection.getProvider().name().toLowerCase(Locale.ROOT);
    }

    private static String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static String safeMessage(RuntimeException ex) {
        String msg = ex.getMessage();
        if (msg == null) {
            return "";
        }
        return msg.length() > 256 ? msg.substring(0, 256) : msg;
    }
}
