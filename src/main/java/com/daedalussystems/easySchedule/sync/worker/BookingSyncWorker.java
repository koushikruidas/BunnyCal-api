package com.daedalussystems.easySchedule.sync.worker;

import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.retry.SyncRetryPolicy;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(BookingSyncWorker.class);

    private final CalendarSyncJobRepository syncJobRepository;
    private final CalendarService calendarService;
    private final SyncRetryPolicy retryPolicy;

    public BookingSyncWorker(CalendarSyncJobRepository syncJobRepository,
                             CalendarService calendarService,
                             SyncRetryPolicy retryPolicy) {
        this.syncJobRepository = syncJobRepository;
        this.calendarService = calendarService;
        this.retryPolicy = retryPolicy;
    }

    @Transactional
    public int processPending(int batchSize) {
        List<UUID> claimedIds = syncJobRepository.claimPendingBatch(Instant.now(), batchSize);
        for (UUID id : claimedIds) {
            syncJobRepository.findById(id).ifPresent(this::processOne);
        }
        return claimedIds.size();
    }

    private void processOne(CalendarSyncJob job) {
        try {
            switch (job.getDesiredAction()) {
                case CREATE -> processCreate(job);
                case UPDATE -> processUpdate(job);
                case DELETE -> processDelete(job);
            }
        } catch (CalendarClientException ex) {
            handleFailure(job, classify(ex));
        } catch (RuntimeException ex) {
            handleFailure(job, "PROVIDER_DOWN");
        }
    }

    private void processCreate(CalendarSyncJob job) {
        // Idempotency: if already mapped, no API call.
        if (job.getExternalEventId() != null && !job.getExternalEventId().isBlank()) {
            syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
            return;
        }
        CalendarService.CreateEventResult result = calendarService.createEvent(
                new CalendarService.CreateCalendarEventCommand(
                        job.getInternalRefId(),
                        job.getProvider(),
                        job.getProvider() + ":" + job.getInternalRefId()
                ));
        if (result.status() == CalendarService.CreateEventStatus.SUCCESS) {
            syncJobRepository.markSynced(job.getId(), job.getVersion(), result.externalEventId());
            return;
        }
        handleFailure(job, result.errorCode() == null ? "PROVIDER_ERROR" : result.errorCode());
    }

    private void processUpdate(CalendarSyncJob job) {
        if (job.getExternalEventId() == null || job.getExternalEventId().isBlank()) {
            // Cannot update without existing mapping: fallback to create.
            processCreate(job);
            return;
        }
        String externalId = calendarService.updateEvent(
                new CalendarService.UpdateCalendarEventCommand(
                        job.getInternalRefId(),
                        job.getProvider(),
                        job.getExternalEventId(),
                        job.getProvider() + ":" + job.getInternalRefId()
                ));
        syncJobRepository.markSynced(job.getId(), job.getVersion(), externalId);
    }

    private void processDelete(CalendarSyncJob job) {
        if (job.getExternalEventId() != null && !job.getExternalEventId().isBlank()) {
            calendarService.deleteEvent(new CalendarService.DeleteCalendarEventCommand(
                    job.getInternalRefId(),
                    job.getProvider(),
                    job.getExternalEventId()
            ));
        }
        syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
    }

    private void handleFailure(CalendarSyncJob job, String errorCode) {
        String code = errorCode == null ? "PROVIDER_ERROR" : errorCode;
        boolean permanent = isPermanent(code) || retryPolicy.isRetryExhausted(job.getAttemptCount() + 1);
        syncJobRepository.markFailure(
                job.getId(),
                job.getVersion(),
                retryPolicy.nextRetryAt(job.getAttemptCount() + 1),
                code,
                permanent
        );
        if (permanent) {
            log.warn("sync job permanently failed jobId={} code={}", job.getId(), code);
        }
    }

    private static boolean isPermanent(String errorCode) {
        return "INVALID_REQUEST".equals(errorCode) || "AUTH_REVOKED".equals(errorCode);
    }

    private static String classify(CalendarClientException ex) {
        int status = ex.getStatusCode();
        if (status == 401) return "AUTH_EXPIRED";
        if (status == 403) return "AUTH_REVOKED";
        if (status == 429) return "RATE_LIMIT";
        if (status >= 500) return "PROVIDER_DOWN";
        if (status >= 400) return "INVALID_REQUEST";
        return "PROVIDER_ERROR";
    }
}
