package io.bunnycal.calendar.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CalendarSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(CalendarSyncScheduler.class);

    // Phase 1 retry suppression (F2). Schema-free: derived from lastErrorAt + age-of-error.
    // The longer a connection has been broken, the longer we wait before the next attempt.
    private static final Duration COOLDOWN_TIER_1 = Duration.ofMinutes(5);
    private static final Duration COOLDOWN_TIER_2 = Duration.ofMinutes(30);
    private static final Duration COOLDOWN_TIER_3 = Duration.ofHours(6);
    private static final Duration TIER_2_AFTER = Duration.ofMinutes(30);
    private static final Duration TIER_3_AFTER = Duration.ofHours(6);

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventIngestionService ingestionService;
    private final CalendarSyncClientRegistry syncClientRegistry;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarConnectionWriteService connectionWriteService;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate txTemplate;

    public CalendarSyncScheduler(CalendarConnectionRepository connectionRepository,
                                 CalendarEventIngestionService ingestionService,
                                 CalendarSyncClientRegistry syncClientRegistry,
                                 SlotCacheVersionService slotCacheVersionService,
                                 CalendarConnectionWriteService connectionWriteService,
                                 PlatformTransactionManager transactionManager,
                                 MeterRegistry meterRegistry) {
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
        this.syncClientRegistry = syncClientRegistry;
        this.slotCacheVersionService = slotCacheVersionService;
        this.connectionWriteService = connectionWriteService;
        this.meterRegistry = meterRegistry;
        // Per-connection tx boundary. Outer sync() loop is non-transactional so a slow
        // provider call on connection N never holds a DB connection across the entire
        // candidate list. REQUIRES_NEW because the scheduler runs outside any tx and we
        // want each connection's progress committed independently.
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${calendar.sync.fixed-delay-ms:30000}")
    @SchedulerLock(
            name = "calendar-sync-sweep",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S"
    )
    public void sync() {
        long started = System.nanoTime();
        java.util.List<CalendarConnection> candidates = new java.util.ArrayList<>();
        candidates.addAll(connectionRepository.findByStatus(CalendarConnectionStatus.ACTIVE));
        candidates.addAll(connectionRepository.findByStatus(CalendarConnectionStatus.SYNCING));
        candidates.addAll(connectionRepository.findByStatus(CalendarConnectionStatus.FAILED));
        candidates.addAll(connectionRepository.findByStatus(CalendarConnectionStatus.ERROR));
        log.info("calendar_sync_scheduler_start candidateCount={} elapsedMs={}",
                candidates.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        Instant now = Instant.now();
        for (CalendarConnection connection : candidates) {
            if (isRetrySuppressed(connection, now)) {
                meterRegistry.counter("calendar.sync.retry_suppressed.total",
                                "provider", providerTag(connection),
                                "status", connection.getStatus().name())
                        .increment();
                log.info("calendar_sync_scheduler_retry_suppressed connectionId={} userId={} status={} lastErrorCode={} lastErrorAt={}",
                        connection.getId(), connection.getUserId(), connection.getStatus(),
                        connection.getLastErrorCode(), connection.getLastErrorAt());
                continue;
            }
            try {
                txTemplate.executeWithoutResult(status -> syncOne(connection));
            } catch (RuntimeException ex) {
                // Per-connection tx already rolled back; markFailure was attempted inside.
                // Don't let one bad connection abort the whole sweep.
                log.warn("calendar_sync_scheduler_connection_uncaught connectionId={} userId={}",
                        connection.getId(), connection.getUserId(), ex);
            }
        }
    }

    private void syncOne(CalendarConnection connection) {
        long connectionStart = System.nanoTime();
        CalendarConnectionStatus previousStatus = connection.getStatus();
        String expectedCursor = connection.getProviderSyncCursor();
        ExternalCalendarSyncClient syncClient = syncClientRegistry.clientFor(connection);
        String providerTag = providerTag(connection);
        try {
                ExternalCalendarSyncClient.SyncBatch batch =
                        syncClient.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);
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
     * TokenRefresher writes REVOKED (invalid_grant) or ERROR (other unauthorized) in its own
     * REQUIRES_NEW transaction before re-throwing. Previously this catch unconditionally wrote
     * FAILED, clobbering REVOKED and creating a 30 s retry storm. Now: reload the row, and
     * only downgrade to FAILED when the latest persisted status is NOT a terminal/classified
     * one. This preserves the upstream OAuth classification end-to-end.
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

        String errorCode = classifySyncError(ex);
        connectionWriteService.markFailure(
                connection.getId(),
                CalendarConnectionStatus.FAILED,
                errorCode,
                Instant.now(),
                "scheduler_sync_failure");
        log.warn("calendar_sync_scheduler_transition connectionId={} userId={} prevStatus={} nextStatus=FAILED errorCode={} elapsedMs={}",
                connection.getId(), connection.getUserId(), previousStatus, errorCode,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectionStart), ex);
    }

    /**
     * F2: skip FAILED/ERROR connections that are still inside their cooldown window.
     * REVOKED is already excluded by the findByStatus query set above; this guards against
     * mistaken inclusion and is also explicit defense-in-depth.
     */
    boolean isRetrySuppressed(CalendarConnection connection, Instant now) {
        CalendarConnectionStatus status = connection.getStatus();
        if (status == CalendarConnectionStatus.REVOKED) {
            return true;
        }
        if (status != CalendarConnectionStatus.FAILED && status != CalendarConnectionStatus.ERROR) {
            return false;
        }
        Instant lastErrorAt = connection.getLastErrorAt();
        if (lastErrorAt == null) {
            // No timestamp recorded — let it through so we can classify and stamp lastErrorAt.
            return false;
        }
        Duration cooldown = cooldownFor(lastErrorAt, now);
        return lastErrorAt.plus(cooldown).isAfter(now);
    }

    private static Duration cooldownFor(Instant lastErrorAt, Instant now) {
        Duration age = Duration.between(lastErrorAt, now);
        if (age.compareTo(TIER_3_AFTER) >= 0) {
            return COOLDOWN_TIER_3;
        }
        if (age.compareTo(TIER_2_AFTER) >= 0) {
            return COOLDOWN_TIER_2;
        }
        return COOLDOWN_TIER_1;
    }

    private static boolean isTerminalOrAlreadyClassified(CalendarConnectionStatus status) {
        return status == CalendarConnectionStatus.REVOKED || status == CalendarConnectionStatus.ERROR;
    }

    private static String classifySyncError(RuntimeException ex) {
        String message = ex.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("invalid_grant")) {
                return "invalid_grant";
            }
            if (normalized.contains("invalid_token")) {
                return "invalid_token";
            }
            if (normalized.contains("unauthorized")) {
                return "unauthorized";
            }
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
