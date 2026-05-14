package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.domain.ProviderEventProjection;
import com.daedalussystems.easySchedule.calendar.repository.ProviderEventProjectionRepository;
import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository;
import com.daedalussystems.easySchedule.sync.invariants.CompositeSyncStateClassifier;
import com.daedalussystems.easySchedule.sync.invariants.LineageContext;
import com.daedalussystems.easySchedule.sync.invariants.SyncInvariantMonitor;
import com.daedalussystems.easySchedule.sync.orchestration.ExternalTerminalDeleteConvergenceService;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ProviderEventProjectionService {
    private static final Logger log = LoggerFactory.getLogger(ProviderEventProjectionService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ProviderEventProjectionRepository repository;
    private final ProviderEventVersionComparator comparator;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate txTemplate;
    private final SyncInvariantMonitor invariantMonitor;
    private final CalendarEventMappingRepository mappingRepository;
    private final ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService;
    private final boolean acceptAmbiguous;

    public ProviderEventProjectionService(ProviderEventProjectionRepository repository,
                                          ProviderEventVersionComparator comparator,
                                          MeterRegistry meterRegistry,
                                          PlatformTransactionManager transactionManager,
                                          SyncInvariantMonitor invariantMonitor,
                                          CalendarEventMappingRepository mappingRepository,
                                          ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService,
                                          @Value("${sync.projection.accept-ambiguous:true}") boolean acceptAmbiguous) {
        this.repository = repository;
        this.comparator = comparator;
        this.meterRegistry = meterRegistry;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.invariantMonitor = invariantMonitor;
        this.mappingRepository = mappingRepository;
        this.terminalDeleteConvergenceService = terminalDeleteConvergenceService;
        this.acceptAmbiguous = acceptAmbiguous;
    }

    public boolean shouldApplyAndAdvance(UUID connectionId,
                                         String provider,
                                         CalendarEventIngestionService.IncomingCalendarEvent incoming) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                boolean applied = txTemplate.execute(status -> applyOnce(connectionId, provider, incoming));
                if (attempt > 1) {
                    counter("sync.projection.retry.success.total", provider, "attempt", String.valueOf(attempt)).increment();
                }
                return Boolean.TRUE.equals(applied);
            } catch (DataIntegrityViolationException ex) {
                counter("sync.projection.concurrent_retry.total", provider, "reason", "unique_conflict").increment();
                if (attempt == MAX_ATTEMPTS) {
                    counter("sync.projection.retry.exhausted.total", provider, "reason", "unique_conflict").increment();
                    throw ex;
                }
            } catch (PessimisticLockingFailureException ex) {
                counter("sync.projection.lock_failure.total", provider, "reason", classifyLockFailure(ex)).increment();
                if (attempt == MAX_ATTEMPTS) {
                    counter("sync.projection.retry.exhausted.total", provider, "reason", "lock_failure").increment();
                    throw ex;
                }
            }
        }
        return false;
    }

    @Transactional
    protected boolean applyOnce(UUID connectionId,
                                String provider,
                                CalendarEventIngestionService.IncomingCalendarEvent incoming) {
        long startedNanos = System.nanoTime();
        try {
            Optional<ProviderEventProjection> existing = repository.findWithLockByConnectionIdAndProviderAndExternalEventId(
                    connectionId, provider, incoming.externalEventId());
            UUID linkedBookingId = resolveBookingId(connectionId, provider, incoming.externalEventId(), existing);
            LineageContext lineage = new LineageContext(
                    safeMdc("correlationId"),
                    safeMdc("causationId"),
                    linkedBookingId == null ? safeMdc("bookingId") : linkedBookingId.toString(),
                    incoming.externalEventId(),
                    "",
                    safeMdc("terminalIntentEpoch"));

            ProviderEventVersionComparator.VersionVector incomingVector = new ProviderEventVersionComparator.VersionVector(
                    incoming.providerSequence(),
                    incoming.providerUpdatedAt(),
                    incoming.providerEtag(),
                    incoming.payloadHash());
            ProviderEventVersionComparator.VersionVector persistedVector = existing
                    .map(e -> new ProviderEventVersionComparator.VersionVector(
                            e.getProviderSequence(), e.getProviderUpdatedAt(), e.getProviderEtag(), e.getPayloadHash()))
                    .orElse(null);

            ProviderEventVersionComparator.ComparisonResult compare = comparator.compare(incomingVector, persistedVector);
            log.info("projection_apply_attempt provider={} connectionId={} externalEventId={} compareResult={} incomingSequence={} persistedSequence={} incomingUpdatedAt={} persistedUpdatedAt={}",
                    provider,
                    connectionId,
                    incoming.externalEventId(),
                    compare,
                    incoming.providerSequence(),
                    existing.map(ProviderEventProjection::getProviderSequence).orElse(null),
                    incoming.providerUpdatedAt(),
                    existing.map(ProviderEventProjection::getProviderUpdatedAt).orElse(null));
            if (compare == ProviderEventVersionComparator.ComparisonResult.OLDER_OR_EQUAL) {
                counter("sync.projection.rejected.total", provider, "reason", "older_or_equal").increment();
                log.info("projection_observation_rejected provider={} reason=older_or_equal {}",
                        provider, lineage.asLogLine());
                log.info("projection_apply_result provider={} connectionId={} externalEventId={} applied=false reason=older_or_equal",
                        provider, connectionId, incoming.externalEventId());
                return false;
            }
            if (compare == ProviderEventVersionComparator.ComparisonResult.AMBIGUOUS_NEWER_HINT) {
                if (!acceptAmbiguous) {
                    counter("sync.projection.ambiguous.total", provider, "decision", "rejected").increment();
                    counter("sync.projection.rejected.total", provider, "reason", "ambiguous_rejected").increment();
                    log.info("projection_observation_rejected provider={} reason=ambiguous_disabled {}",
                            provider, lineage.asLogLine());
                    log.info("projection_apply_result provider={} connectionId={} externalEventId={} applied=false reason=ambiguous_disabled",
                            provider, connectionId, incoming.externalEventId());
                    return false;
                }
                counter("sync.projection.ambiguous.total", provider, "decision", "accepted").increment();
                counter("sync.projection.accepted.total", provider, "reason", "ambiguous_newer_hint").increment();
            } else {
                counter("sync.projection.accepted.total", provider, "reason", "strict_newer").increment();
            }

            ProviderEventProjection projection = existing.orElseGet(ProviderEventProjection::new);
            if (existing.isPresent()
                    && "TOMBSTONED_HARD".equals(existing.get().getProjectionStatus())
                    && !incoming.cancelled()) {
                counter("sync.projection.resurrection_attempt.total", provider, "reason", "hard_tombstone_to_active").increment();
                counter("sync.projection.resurrection_blocked.total", provider, "reason", "hard_tombstone_guard").increment();
                counter("sync.projection.rejected.total", provider, "reason", "hard_tombstone_guard").increment();
                log.info("projection_observation_rejected provider={} reason=hard_tombstone_guard {}",
                        provider, lineage.asLogLine());
                log.info("projection_apply_result provider={} connectionId={} externalEventId={} applied=false reason=hard_tombstone_guard",
                        provider, connectionId, incoming.externalEventId());
                return false;
            }
            projection.setConnectionId(connectionId);
            projection.setBookingId(linkedBookingId);
            projection.setProvider(provider);
            projection.setExternalEventId(incoming.externalEventId());
            projection.setProjectionStatus(incoming.cancelled() ? "TOMBSTONED_SOFT" : "ACTIVE");
            projection.setProjectionVersion(existing.map(e -> e.getProjectionVersion() + 1).orElse(1L));
            projection.setProviderSequence(incoming.providerSequence());
            projection.setProviderUpdatedAt(incoming.providerUpdatedAt());
            projection.setProviderEtag(incoming.providerEtag());
            projection.setPayloadHash(incoming.payloadHash());
            projection.setLastObservedAt(Instant.now());
            repository.saveAndFlush(projection);
            log.info("projection_observation_applied provider={} projection_version={} terminal_intent_epoch={} {}",
                    provider,
                    projection.getProjectionVersion(),
                    safeMdc("terminalIntentEpoch"),
                    lineage.asLogLine());
            log.info("projection_apply_result provider={} connectionId={} externalEventId={} applied=true projectionVersion={} projectionStatus={}",
                    provider, connectionId, incoming.externalEventId(), projection.getProjectionVersion(), projection.getProjectionStatus());
            if (incoming.cancelled()) {
                if (linkedBookingId == null) {
                    counter("sync.projection.booking_linkage.total", provider, "result", "missing").increment();
                    log.warn("provider_tombstone_booking_linkage_failed provider={} connectionId={} externalEventId={} reason=no_deterministic_booking",
                            provider, connectionId, incoming.externalEventId());
                } else {
                    var result = terminalDeleteConvergenceService.convergeProviderTombstone(
                            linkedBookingId,
                            provider,
                            incoming.externalEventId(),
                            "provider_projection");
                    log.info("provider_tombstone_terminal_convergence provider={} connectionId={} bookingId={} externalEventId={} lifecycleRows={} bookingRows={} result={}",
                            provider,
                            connectionId,
                            linkedBookingId,
                            incoming.externalEventId(),
                            result.lifecycleRows(),
                            result.bookingRows(),
                            result.result());
                }
            }
            invariantMonitor.assertState(
                    "projection_mutation",
                    incoming.cancelled() ? BookingState.CANCELLED : BookingState.CONFIRMED,
                    SyncJobStatus.SYNCED,
                    incoming.cancelled()
                            ? CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_SOFT
                            : CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                    CompositeSyncStateClassifier.ParticipationLifecycle.NEEDS_ACTION,
                    new LineageContext(
                            lineage.correlationId(),
                            lineage.causationId(),
                            lineage.bookingId(),
                            lineage.externalEventId(),
                            String.valueOf(projection.getProjectionVersion()),
                            lineage.terminalIntentEpoch()));
            return true;
        } finally {
            // End-to-end apply transaction duration (includes lock acquisition + comparison + persistence).
            long applyDurationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            meterRegistry.timer("sync.projection.apply.duration.ms", "provider", provider)
                    .record(applyDurationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private UUID resolveBookingId(UUID connectionId,
                                  String provider,
                                  String externalEventId,
                                  Optional<ProviderEventProjection> existing) {
        if (existing.isPresent() && existing.get().getBookingId() != null) {
            counter("sync.projection.booking_linkage.total", provider, "result", "existing").increment();
            return existing.get().getBookingId();
        }
        CalendarEventMappingRepository.BookingLinkageResult linkage =
                mappingRepository.findUniqueBookingForProviderEvent(connectionId, provider, externalEventId);
        if (linkage.bookingId().isPresent()) {
            counter("sync.projection.booking_linkage.total", provider, "result", "linked").increment();
            log.info("provider_event_booking_linked provider={} connectionId={} externalEventId={} bookingId={} reason={} matches={}",
                    provider, connectionId, externalEventId, linkage.bookingId().get(), linkage.reason(), linkage.matches());
            return linkage.bookingId().get();
        }
        counter("sync.projection.booking_linkage.total", provider, "result", linkage.reason()).increment();
        log.info("provider_event_booking_linkage_absent provider={} connectionId={} externalEventId={} reason={} matches={}",
                provider, connectionId, externalEventId, linkage.reason(), linkage.matches());
        return null;
    }

    private Counter counter(String name, String provider, String tagName, String tagValue) {
        return meterRegistry.counter(name, "provider", provider, tagName, tagValue);
    }

    private static String classifyLockFailure(RuntimeException ex) {
        if (ex instanceof DeadlockLoserDataAccessException) {
            return "deadlock";
        }
        return "timeout_or_lock";
    }

    private static String safeMdc(String key) {
        String v = MDC.get(key);
        return v == null ? "" : v;
    }
}
