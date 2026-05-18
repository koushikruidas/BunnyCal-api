package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventIngestionService ingestionService;
    private final ExternalCalendarSyncClient syncClient;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarConnectionWriteService connectionWriteService;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate txTemplate;

    public CalendarSyncScheduler(CalendarConnectionRepository connectionRepository,
                                 CalendarEventIngestionService ingestionService,
                                 ExternalCalendarSyncClient syncClient,
                                 SlotCacheVersionService slotCacheVersionService,
                                 CalendarConnectionWriteService connectionWriteService,
                                 PlatformTransactionManager transactionManager,
                                 MeterRegistry meterRegistry) {
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
        this.syncClient = syncClient;
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
    public void sync() {
        long started = System.nanoTime();
        java.util.List<CalendarConnection> candidates = new java.util.ArrayList<>();
        candidates.addAll(connectionRepository.findByStatus(CalendarConnectionStatus.ACTIVE));
        candidates.addAll(connectionRepository.findByStatus(CalendarConnectionStatus.SYNCING));
        candidates.addAll(connectionRepository.findByStatus(CalendarConnectionStatus.FAILED));
        candidates.addAll(connectionRepository.findByStatus(CalendarConnectionStatus.ERROR));
        log.info("calendar_sync_scheduler_start candidateCount={} elapsedMs={}",
                candidates.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        for (CalendarConnection connection : candidates) {
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
        try {
                ExternalCalendarSyncClient.SyncBatch batch =
                        syncClient.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);
                ingestionService.upsertEvents(connection.getId(), batch.events(), SyncSourceAttribution.PULL_SYNC);
                if (batch.events().isEmpty()) {
                    meterRegistry.counter("calendar.sync.provider_drift_detected.total", "provider", "google", "source", "PULL_SYNC")
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
                connectionWriteService.markFailure(
                        connection.getId(),
                        CalendarConnectionStatus.FAILED,
                        "SYNC_FAILED",
                        Instant.now(),
                        "scheduler_sync_failure");
                log.warn("calendar_sync_scheduler_transition connectionId={} userId={} prevStatus={} nextStatus=FAILED mode=failure elapsedMs={}",
                        connection.getId(), connection.getUserId(), previousStatus,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectionStart), ex);
            }
    }
}
