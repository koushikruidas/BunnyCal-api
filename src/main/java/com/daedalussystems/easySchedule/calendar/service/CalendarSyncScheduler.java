package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CalendarSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(CalendarSyncScheduler.class);
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventIngestionService ingestionService;
    private final ExternalCalendarSyncClient syncClient;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarConnectionWriteService connectionWriteService;

    public CalendarSyncScheduler(CalendarConnectionRepository connectionRepository,
                                 CalendarEventIngestionService ingestionService,
                                 ExternalCalendarSyncClient syncClient,
                                 SlotCacheVersionService slotCacheVersionService,
                                 CalendarConnectionWriteService connectionWriteService) {
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
        this.syncClient = syncClient;
        this.slotCacheVersionService = slotCacheVersionService;
        this.connectionWriteService = connectionWriteService;
    }

    @Scheduled(fixedDelayString = "${calendar.sync.fixed-delay-ms:30000}")
    @Transactional
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
            long connectionStart = System.nanoTime();
            CalendarConnectionStatus previousStatus = connection.getStatus();
            try {
                ingestionService.upsertEvents(connection.getId(), syncClient.fetchIncremental(connection));
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
                ingestionService.upsertEvents(connection.getId(), syncClient.fetchFull(connection));
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
}
