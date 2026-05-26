package io.bunnycal.sync.worker;

import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.ownership.BookingOwnershipService;
import io.bunnycal.booking.ownership.BookingOwnership;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.conferencing.service.ConferencingCoordinator;
import io.bunnycal.conferencing.service.ConferencingExecutionPolicy;
import io.bunnycal.conferencing.service.ConferencingExecutionResult;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import io.bunnycal.sync.orchestration.ExternalTerminalDeleteConvergenceService;
import io.bunnycal.sync.orchestration.IdempotencyKeyFactory;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.sync.retry.SyncRetryPolicy;
import io.bunnycal.sync.state.CalendarSyncJob;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

@Service
public class BookingSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(BookingSyncWorker.class);
    private static final String BOOKING_CONFIRMED = "CONFIRMED";

    private final CalendarSyncJobRepository syncJobRepository;
    private final BookingRepository bookingRepository;
    private final CalendarService calendarService;
    private final ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService;
    private final SyncRetryPolicy retryPolicy;
    private final ConnectionRateLimitBreaker rateLimitBreaker;
    private final IdempotencyKeyFactory idempotencyKeyFactory;
    private final ConferencingCoordinator conferencingCoordinator;
    private final ConferencingExecutionPolicy conferencingExecutionPolicy;
    private final BookingOwnershipService bookingOwnershipService;
    private final MeterRegistry meterRegistry;
    private final Counter syncSuccessCount;
    private final Counter syncFailureCount;
    private final Counter retryCount;
    private final Timer syncLatency;
    private final ConcurrentMap<String, Timer> providerLatencyTimers = new ConcurrentHashMap<>();
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public BookingSyncWorker(CalendarSyncJobRepository syncJobRepository,
                             BookingRepository bookingRepository,
                             CalendarService calendarService,
                             ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService,
                             SyncRetryPolicy retryPolicy,
                             ConnectionRateLimitBreaker rateLimitBreaker,
                             IdempotencyKeyFactory idempotencyKeyFactory,
                             ObjectProvider<ConferencingCoordinator> conferencingCoordinatorProvider,
                             ConferencingExecutionPolicy conferencingExecutionPolicy,
                             BookingOwnershipService bookingOwnershipService,
                             PlatformTransactionManager transactionManager,
                             MeterRegistry meterRegistry,
                             ObjectMapper objectMapper) {
        this.syncJobRepository = syncJobRepository;
        this.bookingRepository = bookingRepository;
        this.calendarService = calendarService;
        this.terminalDeleteConvergenceService = terminalDeleteConvergenceService;
        this.retryPolicy = retryPolicy;
        this.rateLimitBreaker = rateLimitBreaker;
        this.idempotencyKeyFactory = idempotencyKeyFactory;
        this.conferencingCoordinator = conferencingCoordinatorProvider.getIfAvailable();
        this.conferencingExecutionPolicy = conferencingExecutionPolicy;
        this.bookingOwnershipService = bookingOwnershipService;
        log.info("conferencing_coordinator_injection coordinatorAvailable={}",
                this.conferencingCoordinator != null);
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.syncSuccessCount = meterRegistry.counter("sync_success_count");
        this.syncFailureCount = meterRegistry.counter("sync_failure_count");
        this.retryCount = meterRegistry.counter("retry_count");
        this.syncLatency = Timer.builder("sync_latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    BookingSyncWorker(CalendarSyncJobRepository syncJobRepository,
                      BookingRepository bookingRepository,
                      CalendarService calendarService,
                      ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService,
                      SyncRetryPolicy retryPolicy,
                      ConnectionRateLimitBreaker rateLimitBreaker,
                      IdempotencyKeyFactory idempotencyKeyFactory,
                      ConferencingExecutionPolicy conferencingExecutionPolicy,
                      BookingOwnershipService bookingOwnershipService,
                      PlatformTransactionManager transactionManager,
                      MeterRegistry meterRegistry,
                      ObjectMapper objectMapper) {
        this(syncJobRepository, bookingRepository, calendarService, terminalDeleteConvergenceService, retryPolicy,
                rateLimitBreaker, idempotencyKeyFactory, new DefaultListableBeanFactory().getBeanProvider(ConferencingCoordinator.class),
                conferencingExecutionPolicy, bookingOwnershipService,
                transactionManager, meterRegistry, objectMapper);
    }

    public int processPending(int batchSize) {
        List<UUID> claimedIds = syncJobRepository.claimPendingBatch(Instant.now(), batchSize);
        log.info("sync_job_batch_claim batchSize={} claimedCount={}", batchSize, claimedIds.size());
        for (UUID id : claimedIds) {
            log.info("sync_job_claimed jobId={}", id);
            try {
                txTemplate.executeWithoutResult(status -> syncJobRepository.findById(id).ifPresent(this::processOne));
            } catch (RuntimeException ex) {
                syncFailureCount.increment();
                log.warn("sync_job_uncaught jobId={}", id, ex);
            }
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
            if (rateLimitBreaker.isOpen(rateLimitKey(job))) {
                syncJobRepository.markFailure(job.getId(), job.getVersion(), Instant.now().plusSeconds(300), "RATE_LIMIT_CIRCUIT_OPEN", false);
                return;
            }
            log.info("{{\"event\":\"sync_job_processing\",\"internalRefId\":\"{}\",\"provider\":\"{}\",\"correlationId\":\"{}\",\"action\":\"{}\"}}",
                    job.getInternalRefId(), job.getProvider(), correlationId, job.getDesiredAction());
            if (ownershipVersionMismatch(job)) {
                return;
            }
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
        BookingOwnership ownership = bookingOwnershipService.requireOwnership(job.getInternalRefId());
        if (ownership.getProviderExternalEventId() != null && !ownership.getProviderExternalEventId().isBlank()) {
            meterRegistry.counter("duplicate_projection_write_prevented_total").increment();
            log.warn("duplicate_projection_write_prevented bookingId={} ownershipVersion={} provider={} projectionConnectionId={} externalEventId={} syncJobId={} lifecycleOperation=create",
                    job.getInternalRefId(),
                    ownership.getOwnershipVersion(),
                    ownership.getProjectionProvider(),
                    ownership.getProjectionConnectionId(),
                    ownership.getProviderExternalEventId(),
                    job.getId());
            syncJobRepository.markSyncedWithMetadata(
                    job.getId(),
                    job.getVersion(),
                    ownership.getProviderExternalEventId(),
                    job.getProviderEventUrl(),
                    job.getConferenceUrl(),
                    job.getConferenceProvider(),
                    job.getConferenceMetadataJson());
            return;
        }
        if (job.getExternalEventId() != null && !job.getExternalEventId().isBlank()) {
            syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
            return;
        }
        var state = resolveState(job);
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
        ConferencingExecutionResult conferencingResult = resolveConferencingInstructionForCreate(job);
        ConferencingInstruction instruction = conferencingResult.instruction();
        CalendarService.CreateEventResult result = calendarService.createEvent(
                new CalendarService.CreateCalendarEventCommand(
                        job.getInternalRefId(),
                        job.getProvider(),
                        idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId()),
                        instruction,
                        job.getSchedulingConnectionId()
                ));
        providerLatency(job.getProvider()).record(java.time.Duration.between(startedAt, Instant.now()));
        if (result.status() == CalendarService.CreateEventStatus.SUCCESS) {
            syncJobRepository.markSyncedWithMetadata(
                    job.getId(),
                    job.getVersion(),
                    result.externalEventId(),
                    result.providerEventUrl(),
                    resolveConferenceUrl(result, instruction),
                    resolveConferenceProvider(instruction),
                    toConferenceMetadataJson(instruction, conferencingResult));
            BookingOwnershipService.LinkageAttachResult attachResult =
                    bookingOwnershipService.attachExternalEventIdResult(job.getInternalRefId(), result.externalEventId());
            if (attachResult == BookingOwnershipService.LinkageAttachResult.CONFLICT) {
                meterRegistry.counter("external_event_linkage_conflict_total").increment();
            }
            return;
        }
        handleFailure(job, result.errorCode() == null ? "PROVIDER_ERROR" : result.errorCode());
    }

    private void processUpdate(CalendarSyncJob job) {
        BookingOwnership ownership = bookingOwnershipService.requireOwnership(job.getInternalRefId());
        String authoritativeExternalEventId = resolveAuthoritativeExternalEventId(job, ownership, "update");
        if (authoritativeExternalEventId == null || authoritativeExternalEventId.isBlank()) {
            processCreate(job);
            return;
        }
        Instant startedAt = Instant.now();
        ConferencingExecutionResult conferencingResult = resolveConferencingInstructionForUpdate(job);
        ConferencingInstruction instruction = conferencingResult.instruction();
        String externalId = calendarService.updateEvent(
                new CalendarService.UpdateCalendarEventCommand(
                        job.getInternalRefId(),
                        job.getProvider(),
                        authoritativeExternalEventId,
                        idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId()),
                        instruction,
                        job.getSchedulingConnectionId()
                ));
        providerLatency(job.getProvider()).record(java.time.Duration.between(startedAt, Instant.now()));
        syncJobRepository.markSyncedWithMetadata(
                job.getId(),
                job.getVersion(),
                externalId,
                job.getProviderEventUrl(),
                resolveConferenceUrl(null, instruction),
                resolveConferenceProvider(instruction),
                toConferenceMetadataJson(instruction, conferencingResult));
        bookingOwnershipService.attachExternalEventIdResult(job.getInternalRefId(), externalId);
    }

    private boolean ownershipVersionMismatch(CalendarSyncJob job) {
        BookingOwnership ownership = bookingOwnershipService.requireOwnership(job.getInternalRefId());
        if (ownership.getOwnershipVersion() == job.getOwnershipVersion()) {
            return false;
        }
        meterRegistry.counter("ownership_version_mismatch_total").increment();
        log.warn("ownership_version_mismatch_detected bookingId={} ownershipVersion={} jobOwnershipVersion={} provider={} projectionConnectionId={} externalEventId={} syncJobId={} lifecycleOperation={}",
                job.getInternalRefId(),
                ownership.getOwnershipVersion(),
                job.getOwnershipVersion(),
                ownership.getProjectionProvider(),
                ownership.getProjectionConnectionId(),
                ownership.getProviderExternalEventId(),
                job.getId(),
                job.getDesiredAction());
        syncJobRepository.markSyncedFromProcessingWithLifecycle(
                job.getId(),
                job.getVersion(),
                job.getExternalEventId(),
                "STALE_OWNERSHIP_VERSION");
        log.warn("stale_sync_job_skipped bookingId={} ownershipVersion={} provider={} projectionConnectionId={} externalEventId={} syncJobId={} lifecycleOperation={}",
                job.getInternalRefId(),
                ownership.getOwnershipVersion(),
                ownership.getProjectionProvider(),
                ownership.getProjectionConnectionId(),
                ownership.getProviderExternalEventId(),
                job.getId(),
                job.getDesiredAction());
        return true;
    }

    private BookingRepository.BookingStateRow resolveState(CalendarSyncJob job) {
        if (job.getPartitionKey() != null) {
            return bookingRepository.findStateByIdAndHostId(job.getInternalRefId(), job.getPartitionKey()).orElse(null);
        }
        return bookingRepository.findStateById(job.getInternalRefId()).orElse(null);
    }

    private void processDelete(CalendarSyncJob job) {
        cancelConferencing(job);
        BookingOwnership ownership = bookingOwnershipService.requireOwnership(job.getInternalRefId());
        String authoritativeExternalEventId = resolveAuthoritativeExternalEventId(job, ownership, "delete");
        boolean idempotentProviderMissing = false;
        if (authoritativeExternalEventId != null && !authoritativeExternalEventId.isBlank()) {
            Instant startedAt = Instant.now();
            try {
                calendarService.deleteEvent(new CalendarService.DeleteCalendarEventCommand(
                        job.getInternalRefId(),
                        job.getProvider(),
                        authoritativeExternalEventId,
                        job.getSchedulingConnectionId()
                ));
            } catch (CalendarClientException ex) {
                if (isDeleteAlreadyConverged(ex)) {
                    log.info("sync_delete_idempotent_success syncJobId={} bookingId={} provider={} externalEventId={} status={}",
                            job.getId(), job.getInternalRefId(), job.getProvider(), authoritativeExternalEventId, ex.getStatusCode());
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
                    job.getId(), job.getInternalRefId(), job.getProvider(),
                    ExternalTerminalDeleteConvergenceService.LIFECYCLE_STATE,
                    result.lifecycleRows(), result.bookingRows(), result.result());
            return;
        }
        syncJobRepository.markSynced(job.getId(), job.getVersion(), job.getExternalEventId());
    }

    private String resolveAuthoritativeExternalEventId(CalendarSyncJob job,
                                                       BookingOwnership ownership,
                                                       String lifecycleOperation) {
        String ownershipExternalEventId = ownership.getProviderExternalEventId();
        String jobExternalEventId = job.getExternalEventId();
        if (ownershipExternalEventId != null && !ownershipExternalEventId.isBlank()) {
            if (jobExternalEventId != null
                    && !jobExternalEventId.isBlank()
                    && !ownershipExternalEventId.equals(jobExternalEventId)) {
                meterRegistry.counter("lifecycle_authority_external_event_mismatch_total").increment();
                log.warn("lifecycle_authority_external_event_mismatch bookingId={} ownershipVersion={} projectionProvider={} projectionConnectionId={} jobExternalEventId={} authoritativeExternalEventId={} syncJobId={} lifecycleOperation={}",
                        job.getInternalRefId(),
                        ownership.getOwnershipVersion(),
                        ownership.getProjectionProvider(),
                        ownership.getProjectionConnectionId(),
                        jobExternalEventId,
                        ownershipExternalEventId,
                        job.getId(),
                        lifecycleOperation);
            }
            return ownershipExternalEventId;
        }
        return jobExternalEventId;
    }

    private void cancelConferencing(CalendarSyncJob job) {
        if (conferencingCoordinator == null) {
            return;
        }
        UUID hostId = resolveHostId(job);
        if (hostId == null) {
            return;
        }
        conferencingCoordinator.cancelForBooking(job.getInternalRefId(), hostId);
    }

    private ConferencingExecutionResult resolveConferencingInstructionForCreate(CalendarSyncJob job) {
        if (conferencingCoordinator == null) {
            log.info("conferencing_instruction_fallback_none reason=coordinator_null bookingId={} action=CREATE",
                    job.getInternalRefId());
            return ConferencingExecutionResult.degraded(ConferencingInstruction.none(), "coordinator_null");
        }
        UUID hostId = resolveHostId(job);
        if (hostId == null) {
            log.info("conferencing_instruction_fallback_none reason=host_null bookingId={} action=CREATE",
                    job.getInternalRefId());
            return ConferencingExecutionResult.degraded(ConferencingInstruction.none(), "host_null");
        }
        ConferencingInstruction instruction = conferencingCoordinator.prepareForCreate(job.getInternalRefId(), hostId);
        ConferencingExecutionResult result = conferencingExecutionPolicy.adaptForMirrorProvider(
                instruction, job.getProvider(), job.getInternalRefId(), "CREATE");
        log.info("conferencing_instruction_resolved bookingId={} action=CREATE provider={} mode={} hasJoinUrl={} outcome={} reasonCode={}",
                job.getInternalRefId(), result.instruction().providerType(), result.instruction().mode(),
                result.instruction().embedsExternalUrl(), result.outcome(), result.reasonCode());
        return result;
    }

    private static String resolveConferenceProvider(ConferencingInstruction instruction) {
        if (instruction == null || instruction.providerType() == null) {
            return null;
        }
        return instruction.providerType().name();
    }

    private static String resolveConferenceUrl(CalendarService.CreateEventResult result,
                                               ConferencingInstruction instruction) {
        if (result != null && result.conferenceUrl() != null && !result.conferenceUrl().isBlank()) {
            return result.conferenceUrl();
        }
        if (instruction != null && instruction.joinUrl() != null && !instruction.joinUrl().isBlank()) {
            return instruction.joinUrl();
        }
        return null;
    }

    private String toConferenceMetadataJson(ConferencingInstruction instruction, ConferencingExecutionResult result) {
        if (instruction == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "mode", String.valueOf(instruction.mode()),
                    "meetingId", instruction.meetingId() == null ? "" : instruction.meetingId(),
                    "hostUrl", instruction.hostUrl() == null ? "" : instruction.hostUrl(),
                    "executionOutcome", result == null ? "APPLIED" : result.outcome().name(),
                    "executionReasonCode", result == null || result.reasonCode() == null ? "" : result.reasonCode()
            ));
        } catch (JsonProcessingException ex) {
            log.warn("sync_job_conference_metadata_serialize_failed bookingId={} message={}",
                    MDC.get("bookingId"), ex.getMessage());
            return null;
        }
    }

    private ConferencingExecutionResult resolveConferencingInstructionForUpdate(CalendarSyncJob job) {
        if (conferencingCoordinator == null) {
            log.info("conferencing_instruction_fallback_none reason=coordinator_null bookingId={} action=UPDATE",
                    job.getInternalRefId());
            return ConferencingExecutionResult.degraded(ConferencingInstruction.none(), "coordinator_null");
        }
        UUID hostId = resolveHostId(job);
        if (hostId == null) {
            log.info("conferencing_instruction_fallback_none reason=host_null bookingId={} action=UPDATE",
                    job.getInternalRefId());
            return ConferencingExecutionResult.degraded(ConferencingInstruction.none(), "host_null");
        }
        ConferencingInstruction instruction = conferencingCoordinator.prepareForUpdate(job.getInternalRefId(), hostId);
        ConferencingExecutionResult result = conferencingExecutionPolicy.adaptForMirrorProvider(
                instruction, job.getProvider(), job.getInternalRefId(), "UPDATE");
        log.info("conferencing_instruction_resolved bookingId={} action=UPDATE provider={} mode={} hasJoinUrl={} outcome={} reasonCode={}",
                job.getInternalRefId(), result.instruction().providerType(), result.instruction().mode(),
                result.instruction().embedsExternalUrl(), result.outcome(), result.reasonCode());
        return result;
    }

    private UUID resolveHostId(CalendarSyncJob job) {
        if (job.getPartitionKey() != null) {
            return job.getPartitionKey();
        }
        BookingRepository.BookingStateRow state = resolveState(job);
        return state == null ? null : state.getHostId();
    }

    private void handleFailure(CalendarSyncJob job, String errorCode) {
        String code = errorCode == null ? "PROVIDER_ERROR" : errorCode;
        if ("RATE_LIMIT".equals(code)) {
            rateLimitBreaker.recordRateLimit(job.getProvider(), rateLimitKey(job));
        }
        boolean permanent = isPermanent(code) || retryPolicy.isRetryExhausted(job.getAttemptCount() + 1);
        syncFailureCount.increment();
        if (!permanent) {
            retryCount.increment();
        }
        syncJobRepository.markFailure(job.getId(), job.getVersion(), retryPolicy.nextRetryAt(job.getAttemptCount() + 1), code, permanent);
        log.warn("{{\"event\":\"sync_job_failure\",\"internalRefId\":\"{}\",\"provider\":\"{}\",\"correlationId\":\"{}\",\"errorCode\":\"{}\",\"permanent\":{}}}",
                job.getInternalRefId(), job.getProvider(), job.getId(), code, permanent);
        if (permanent) {
            meterRegistry.counter("sync_dead_letter_total", "provider", job.getProvider(), "errorCode", code).increment();
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

    private static String rateLimitKey(CalendarSyncJob job) {
        if (job.getPartitionKey() != null) {
            return job.getPartitionKey().toString();
        }
        return job.getInternalRefId().toString();
    }
}
