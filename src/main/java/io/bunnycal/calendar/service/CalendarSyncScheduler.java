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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
    private final int batchSize;
    private final int maxConnectionsPerTick;

    public CalendarSyncScheduler(CalendarConnectionRepository connectionRepository,
                                 CalendarEventIngestionService ingestionService,
                                 CalendarSyncClientRegistry syncClientRegistry,
                                 SlotCacheVersionService slotCacheVersionService,
                                 CalendarConnectionWriteService connectionWriteService,
                                 ProviderConcurrencyGate concurrencyGate,
                                 PlatformTransactionManager transactionManager,
                                 MeterRegistry meterRegistry,
                                 @Value("${calendar.sync.batch-size:100}") int batchSize,
                                 @Value("${calendar.sync.max-connections-per-tick:2000}") int maxConnectionsPerTick) {
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
        this.syncClientRegistry = syncClientRegistry;
        this.slotCacheVersionService = slotCacheVersionService;
        this.connectionWriteService = connectionWriteService;
        this.concurrencyGate = concurrencyGate;
        this.meterRegistry = meterRegistry;
        this.batchSize = Math.max(1, batchSize);
        this.maxConnectionsPerTick = Math.max(this.batchSize, maxConnectionsPerTick);
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
    }

    @Scheduled(fixedDelayString = "${calendar.sync.fixed-delay-ms:30000}")
    @SchedulerLock(
            name = "calendar-sync-sweep",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S"
    )
    public void sync() {
        long sweepStart = System.nanoTime();
        Instant now = Instant.now();

        // Phase 3 batching: paginate the due-query so we never load the entire candidate
        // set into memory. Ordered deterministically (NULLS FIRST nextRetryAt, then id) so
        // pagination is stable across calls and across ticks.
        int processed = 0;
        int deferred = 0;
        int pageIndex = 0;
        while (processed < maxConnectionsPerTick) {
            int remaining = maxConnectionsPerTick - processed;
            int pageLimit = Math.min(batchSize, remaining);
            List<CalendarConnection> page = connectionRepository.findDueForSyncBatch(
                    now, PageRequest.of(pageIndex, pageLimit));
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

        long elapsedNanos = System.nanoTime() - sweepStart;
        sweepTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
        log.info("calendar_sync_scheduler_start processed={} deferred={} pages={} batchSize={} elapsedMs={}",
                processed, deferred, pageIndex + 1, batchSize, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
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
        log.info("calendar_incremental_sync_start connectionId={} userId={} provider={} hasCursor={}",
                connection.getId(), connection.getUserId(), providerTag, expectedCursor != null);
        try {
                ExternalCalendarSyncClient.SyncBatch batch =
                        syncClient.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);
                log.info("calendar_incremental_sync_batch_received connectionId={} provider={} eventCount={} isFullResync={} nextCursorPresent={}",
                        connection.getId(), providerTag, batch.events().size(), batch.fullResyncWindow(), batch.nextCursor() != null);
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
                    slotCacheVersionService.bumpVersion(connection.getUserId());
                }
                connectionWriteService.markActive(
                        connection.getId(),
                        connection.getLastTokenExpiresAt(),
                        connection.getLastSyncedAt(),
                        "scheduler_incremental_success");
                log.info("calendar_sync_scheduler_transition connectionId={} userId={} prevStatus={} nextStatus=ACTIVE mode=incremental elapsedMs={}",
                        connection.getId(), connection.getUserId(), previousStatus,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectionStart));
            } catch (ExternalCalendarSyncClient.SyncTokenInvalidException invalid) {
                connectionWriteService.invalidateProviderCursor(connection.getId(), Instant.now(), "scheduler_sync_cursor_invalidated");
                ExternalCalendarSyncClient.SyncBatch fullBatch =
                        syncClient.fetchFull(connection, SyncSourceAttribution.PULL_SYNC);
                ingestionService.upsertEvents(connection.getId(), fullBatch.events(), SyncSourceAttribution.PULL_SYNC);
                if (fullBatch.nextCursor() != null) {
                    connectionWriteService.advanceProviderCursor(
                            connection.getId(), null, fullBatch.nextCursor(), Instant.now(), "scheduler_full_cursor_advance");
                }
                slotCacheVersionService.bumpVersion(connection.getUserId());
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
