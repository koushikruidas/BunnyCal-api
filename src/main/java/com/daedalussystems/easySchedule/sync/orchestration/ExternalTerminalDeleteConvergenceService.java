package com.daedalussystems.easySchedule.sync.orchestration;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.booking.outbox.OutboxPayloadEnvelope;
import com.daedalussystems.easySchedule.booking.outbox.OutboxPublisher;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.InternalRefType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalTerminalDeleteConvergenceService {
    private static final Logger log = LoggerFactory.getLogger(ExternalTerminalDeleteConvergenceService.class);
    public static final String LIFECYCLE_STATE = "TERMINAL_EXTERNAL_DELETE";
    private static final String EXTERNAL_TERMINATED_EVENT_TYPE = "BOOKING_EXTERNAL_TERMINATED";

    private final CalendarSyncJobRepository syncJobRepository;
    private final BookingRepository bookingRepository;
    private final SlotCacheVersionService slotCacheVersionService;
    private final OutboxPublisher outboxPublisher;
    private final MeterRegistry meterRegistry;

    public ExternalTerminalDeleteConvergenceService(CalendarSyncJobRepository syncJobRepository,
                                                    BookingRepository bookingRepository,
                                                    SlotCacheVersionService slotCacheVersionService,
                                                    OutboxPublisher outboxPublisher,
                                                    MeterRegistry meterRegistry) {
        this.syncJobRepository = syncJobRepository;
        this.bookingRepository = bookingRepository;
        this.slotCacheVersionService = slotCacheVersionService;
        this.outboxPublisher = outboxPublisher;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ConvergenceResult convergeProcessingJob(CalendarSyncJob job, String source) {
        int lifecycleRows = syncJobRepository.markSyncedFromProcessingWithLifecycle(
                job.getId(),
                job.getVersion(),
                job.getExternalEventId(),
                LIFECYCLE_STATE);
        return convergeBooking(
                job.getInternalRefId(),
                job.getProvider(),
                job.getExternalEventId(),
                job.getId(),
                source,
                lifecycleRows,
                "processing_job");
    }

    @Transactional
    public ConvergenceResult convergeSyncedJob(CalendarSyncJob job, String source) {
        int lifecycleRows = syncJobRepository.markSyncedLifecycle(
                job.getId(),
                job.getVersion(),
                job.getExternalEventId(),
                LIFECYCLE_STATE);
        return convergeBooking(
                job.getInternalRefId(),
                job.getProvider(),
                job.getExternalEventId(),
                job.getId(),
                source,
                lifecycleRows,
                "synced_job");
    }

    @Transactional
    public ConvergenceResult convergeProviderTombstone(UUID bookingId,
                                                       String provider,
                                                       String externalEventId,
                                                       String source) {
        int lifecycleRows = syncJobRepository.markLifecycleByBookingProviderExternalEvent(
                InternalRefType.BOOKING.name(),
                bookingId,
                provider,
                externalEventId,
                LIFECYCLE_STATE);
        return convergeBooking(
                bookingId,
                provider,
                externalEventId,
                null,
                source,
                lifecycleRows,
                "provider_tombstone");
    }

    private ConvergenceResult convergeBooking(UUID bookingId,
                                              String provider,
                                              String externalEventId,
                                              UUID syncJobId,
                                              String source,
                                              int lifecycleRows,
                                              String lifecyclePath) {
        var before = bookingRepository.findProjectionStateById(bookingId).orElse(null);
        String beforeStatus = before == null ? "MISSING" : before.getStatus();
        boolean terminalBefore = isTerminalStatus(beforeStatus);
        log.info("authoritative_terminal_projection_guard bookingId={} provider={} source={} syncJobId={} externalEventId={} lifecycleState={} lifecycleRows={} lifecyclePath={} lookupFound={} statusBefore={} terminalBefore={} availabilityReleasedAtBefore={}",
                bookingId,
                provider,
                source,
                syncJobId,
                externalEventId,
                LIFECYCLE_STATE,
                lifecycleRows,
                lifecyclePath,
                before != null,
                beforeStatus,
                terminalBefore,
                before == null ? null : before.getAvailabilityReleasedAt());

        if (before == null) {
            metric(source, "missing_booking");
            log.info("authoritative_terminal_projection_noop bookingId={} provider={} source={} lifecycleState={} reason=missing_booking lifecycleRows={} rowsUpdated=0",
                    bookingId, provider, source, LIFECYCLE_STATE, lifecycleRows);
            return new ConvergenceResult(lifecycleRows, 0, "missing_booking");
        }

        if (terminalBefore) {
            metric(source, "already_terminal");
            log.info("authoritative_terminal_projection_noop bookingId={} provider={} source={} lifecycleState={} reason=already_terminal lifecycleRows={} rowsUpdated=0 statusAfter={} availabilityReleasedAtAfter={}",
                    bookingId,
                    provider,
                    source,
                    LIFECYCLE_STATE,
                    lifecycleRows,
                    beforeStatus,
                    before.getAvailabilityReleasedAt());
            return new ConvergenceResult(lifecycleRows, 0, "already_terminal");
        }

        int projected = bookingRepository.projectExternalTerminalToCancelled(bookingId);
        if (projected == 0) {
            var afterNoop = bookingRepository.findProjectionStateById(bookingId).orElse(null);
            metric(source, "state_drift_noop");
            log.info("authoritative_terminal_projection_noop bookingId={} provider={} source={} lifecycleState={} reason=cas_miss_or_state_drift lifecycleRows={} rowsUpdated=0 statusAfter={} availabilityReleasedAtAfter={}",
                    bookingId,
                    provider,
                    source,
                    LIFECYCLE_STATE,
                    lifecycleRows,
                    afterNoop == null ? "MISSING" : afterNoop.getStatus(),
                    afterNoop == null ? null : afterNoop.getAvailabilityReleasedAt());
            return new ConvergenceResult(lifecycleRows, 0, "cas_miss_or_state_drift");
        }

        var after = bookingRepository.findProjectionStateById(bookingId).orElse(null);
        if (after != null) {
            slotCacheVersionService.bumpVersion(after.getHostId());
            meterRegistry.counter("sync.external_terminal_slot_release.total", "source", safeSource(source)).increment();
            log.info("authoritative_terminal_projection_applied bookingId={} hostId={} provider={} source={} syncJobId={} externalEventId={} lifecycleState={} lifecycleRows={} rowsUpdated={} statusAfter={} availabilityReleasedAtAfter={}",
                    bookingId,
                    after.getHostId(),
                    provider,
                    source,
                    syncJobId,
                    externalEventId,
                    LIFECYCLE_STATE,
                    lifecycleRows,
                    projected,
                    after.getStatus(),
                    after.getAvailabilityReleasedAt());
        } else {
            log.info("authoritative_terminal_projection_applied bookingId={} provider={} source={} syncJobId={} externalEventId={} lifecycleState={} lifecycleRows={} rowsUpdated={} statusAfter=MISSING",
                    bookingId, provider, source, syncJobId, externalEventId, LIFECYCLE_STATE, lifecycleRows, projected);
        }
        metric(source, "applied");
        publishExternalTerminalAudit(bookingId, provider, externalEventId, syncJobId);
        return new ConvergenceResult(lifecycleRows, projected, "applied");
    }

    private void publishExternalTerminalAudit(UUID bookingId, String provider, String externalEventId, UUID syncJobId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "EXTERNAL");
        metadata.put("reasonCode", LIFECYCLE_STATE);
        metadata.put("provider", provider);
        metadata.put("syncJobId", syncJobId == null ? null : syncJobId.toString());
        metadata.put("externalEventId", externalEventId);
        metadata.put("correlationId", MDC.get("correlationId"));
        metadata.put("causationId", MDC.get("causationId"));

        outboxPublisher.publish("Booking", bookingId, new OutboxPayloadEnvelope(
                UUID.randomUUID().toString(),
                EXTERNAL_TERMINATED_EVENT_TYPE,
                1,
                Map.of(
                        "bookingId", bookingId.toString(),
                        "provider", provider,
                        "lifecycleState", LIFECYCLE_STATE
                ),
                metadata));
        meterRegistry.counter("sync.external_terminal_audit_emitted.total").increment();
    }

    private void metric(String source, String result) {
        meterRegistry.counter("sync.external_terminal_convergence.total",
                "source", safeSource(source),
                "result", result).increment();
    }

    private static String safeSource(String source) {
        if ("worker_delete".equals(source) || "reconcile".equals(source) || "provider_projection".equals(source)) {
            return source;
        }
        return "other";
    }

    private static boolean isTerminalStatus(String status) {
        if (status == null || status.isBlank() || "MISSING".equals(status)) {
            return false;
        }
        try {
            return BookingState.fromStatus(status).isTerminal();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public record ConvergenceResult(int lifecycleRows, int bookingRows, String result) {
    }
}
