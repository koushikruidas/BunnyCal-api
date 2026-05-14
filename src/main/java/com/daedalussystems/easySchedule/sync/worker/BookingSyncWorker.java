package com.daedalussystems.easySchedule.sync.worker;

import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.orchestration.ExternalTerminalDeleteConvergenceService;
import com.daedalussystems.easySchedule.sync.orchestration.IdempotencyKeyFactory;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.retry.SyncRetryPolicy;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(BookingSyncWorker.class);
    private static final String BOOKING_CONFIRMED = "CONFIRMED";

    private final CalendarSyncJobRepository syncJobRepository;
    private final BookingRepository bookingRepository;
    private final CalendarService calendarService;
    private final ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService;
    private final SyncRetryPolicy retryPolicy;
    private final IdempotencyKeyFactory idempotencyKeyFactory;
    private final MeterRegistry meterRegistry;
    private final Counter syncSuccessCount;
    private final Counter syncFailureCount;
    private final Counter retryCount;
    private final Timer syncLatency;
    private final ConcurrentMap<String, Timer> providerLatencyTimers = new ConcurrentHashMap<>();

    public BookingSyncWorker(CalendarSyncJobRepository syncJobRepository,
                             BookingRepository bookingRepository,
                             CalendarService calendarService,
                             ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService,
                             SyncRetryPolicy retryPolicy,
                             IdempotencyKeyFactory idempotencyKeyFactory,
                             MeterRegistry meterRegistry) {
        this.syncJobRepository = syncJobRepository;
        this.bookingRepository = bookingRepository;
        this.calendarService = calendarService;
        this.terminalDeleteConvergenceService = terminalDeleteConvergenceService;
        this.retryPolicy = retryPolicy;
        this.idempotencyKeyFactory = idempotencyKeyFactory;
        this.meterRegistry = meterRegistry;
        this.syncSuccessCount = meterRegistry.counter("sync_success_count");
        this.syncFailureCount = meterRegistry.counter("sync_failure_count");
        this.retryCount = meterRegistry.counter("retry_count");
        this.syncLatency = Timer.builder("sync_latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Transactional
    public int processPending(int batchSize) {
        List<UUID> claimedIds = syncJobRepository.claimPendingBatch(Instant.now(), batchSize);
        log.info("sync_job_batch_claim batchSize={} claimedCount={}", batchSize, claimedIds.size());
        for (UUID id : claimedIds) {
            log.info("sync_job_claimed jobId={}", id);
            syncJobRepository.findById(id).ifPresent(this::processOne);
        }
        return claimedIds.size();
    }

    private void processOne(CalendarSyncJob job) {
        Instant startedAt = Instant.now();
        String correlationId = job.getId().toString();
        MDC.put("correlationId", correlationId);
        MDC.put("provider", job.getProvider());
        MDC.put("internalRefId", job.getInternalRefId().toString());
        if ("BOOKING".equalsIgnoreCase(job.getInternalRefType().name())) {
            MDC.put("bookingId", job.getInternalRefId().toString());
        }
        try {
            log.info("{{\"event\":\"sync_job_processing\",\"internalRefId\":\"{}\",\"provider\":\"{}\",\"correlationId\":\"{}\",\"action\":\"{}\"}}",
                    job.getInternalRefId(), job.getProvider(), correlationId, job.getDesiredAction());
            switch (job.getDesiredAction()) {
                case CREATE -> processCreate(job);
                case UPDATE -> processUpdate(job);
                case DELETE -> processDelete(job);
            }
            syncSuccessCount.increment();
        } catch (CalendarClientException ex) {
            handleFailure(job, classify(ex));
        } catch (RuntimeException ex) {
            handleFailure(job, "PROVIDER_DOWN");
        } finally {
            syncLatency.record(java.time.Duration.between(startedAt, Instant.now()));
            MDC.remove("bookingId");
            MDC.remove("internalRefId");
            MDC.remove("provider");
            MDC.remove("correlationId");
        }
    }

    private void processCreate(CalendarSyncJob job) {
        // Idempotency: if already mapped, no API call.
        if (job.getExternalEventId() != null && !job.getExternalEventId().isBlank()) {
            syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
            return;
        }
        var state = bookingRepository.findStateById(job.getInternalRefId()).orElse(null);
        if (state == null || !BOOKING_CONFIRMED.equals(state.getStatus())) {
            log.warn("sync_job_skipped_non_confirmed syncJobId={} bookingId={} desiredAction={} bookingStatus={} externalEventId={}",
                    job.getId(), job.getInternalRefId(), job.getDesiredAction(),
                    state == null ? "MISSING" : state.getStatus(), job.getExternalEventId());
            if (job.getExternalEventId() != null && !job.getExternalEventId().isBlank()) {
                log.error("orphan_external_event_detected bookingId={} syncJobId={} bookingStatus={} externalEventId={}",
                        job.getInternalRefId(), job.getId(),
                        state == null ? "MISSING" : state.getStatus(), job.getExternalEventId());
            }
            syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
            return;
        }
        Instant startedAt = Instant.now();
        CalendarService.CreateEventResult result = calendarService.createEvent(
                new CalendarService.CreateCalendarEventCommand(
                        job.getInternalRefId(),
                        job.getProvider(),
                        idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())
                ));
        providerLatency(job.getProvider()).record(java.time.Duration.between(startedAt, Instant.now()));
        // provider latency is recorded around external provider interactions
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
        Instant startedAt = Instant.now();
        String externalId = calendarService.updateEvent(
                new CalendarService.UpdateCalendarEventCommand(
                        job.getInternalRefId(),
                        job.getProvider(),
                        job.getExternalEventId(),
                        idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())
                ));
        providerLatency(job.getProvider()).record(java.time.Duration.between(startedAt, Instant.now()));
        syncJobRepository.markSynced(job.getId(), job.getVersion(), externalId);
    }

    private void processDelete(CalendarSyncJob job) {
        boolean idempotentProviderMissing = false;
        if (job.getExternalEventId() != null && !job.getExternalEventId().isBlank()) {
            Instant startedAt = Instant.now();
            try {
                calendarService.deleteEvent(new CalendarService.DeleteCalendarEventCommand(
                        job.getInternalRefId(),
                        job.getProvider(),
                        job.getExternalEventId()
                ));
            } catch (CalendarClientException ex) {
                if (isDeleteAlreadyConverged(ex)) {
                    log.info("sync_delete_idempotent_success syncJobId={} bookingId={} provider={} externalEventId={} status={}",
                            job.getId(), job.getInternalRefId(), job.getProvider(), job.getExternalEventId(), ex.getStatusCode());
                    idempotentProviderMissing = true;
                } else {
                    throw ex;
                }
            }
            providerLatency(job.getProvider()).record(java.time.Duration.between(startedAt, Instant.now()));
        }
        if (idempotentProviderMissing) {
            var result = terminalDeleteConvergenceService.convergeProcessingJob(job, "worker_delete");
            log.info("sync_delete_terminal_promotion syncJobId={} bookingId={} provider={} lifecycleState={} lifecycleRows={} bookingRows={} result={}",
                    job.getId(),
                    job.getInternalRefId(),
                    job.getProvider(),
                    ExternalTerminalDeleteConvergenceService.LIFECYCLE_STATE,
                    result.lifecycleRows(),
                    result.bookingRows(),
                    result.result());
            return;
        }
        syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
    }

    private void handleFailure(CalendarSyncJob job, String errorCode) {
        String code = errorCode == null ? "PROVIDER_ERROR" : errorCode;
        boolean permanent = isPermanent(code) || retryPolicy.isRetryExhausted(job.getAttemptCount() + 1);
        syncFailureCount.increment();
        if (!permanent) {
            retryCount.increment();
        }
        syncJobRepository.markFailure(
                job.getId(),
                job.getVersion(),
                retryPolicy.nextRetryAt(job.getAttemptCount() + 1),
                code,
                permanent
        );
        log.warn("{{\"event\":\"sync_job_failure\",\"internalRefId\":\"{}\",\"provider\":\"{}\",\"correlationId\":\"{}\",\"errorCode\":\"{}\",\"permanent\":{}}}",
                job.getInternalRefId(), job.getProvider(), job.getId(), code, permanent);
        if (permanent) {
            log.warn("sync job permanently failed jobId={} code={}", job.getId(), code);
        }
    }

    private Timer providerLatency(String provider) {
        return providerLatencyTimers.computeIfAbsent(provider, p -> Timer.builder("provider_latency")
                .tag("provider", p)
                .publishPercentileHistogram()
                .register(meterRegistry));
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

    private static boolean isDeleteAlreadyConverged(CalendarClientException ex) {
        int status = ex.getStatusCode();
        return status == 404 || status == 410;
    }
}
