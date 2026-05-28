package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.ProviderEventProjection;
import io.bunnycal.calendar.domain.ProviderEventProjectionStatus;
import io.bunnycal.calendar.repository.ProviderEventProjectionRepository;
import io.bunnycal.booking.contract.BookingState;
import io.bunnycal.booking.repository.CalendarEventMappingRepository;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.sync.invariants.CompositeSyncStateClassifier;
import io.bunnycal.sync.invariants.LineageContext;
import io.bunnycal.sync.invariants.SyncInvariantMonitor;
import io.bunnycal.sync.orchestration.ExternalTerminalDeleteConvergenceService;
import io.bunnycal.sync.orchestration.ExternalUpdateConvergenceService;
import io.bunnycal.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
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
    private final CalendarSyncJobRepository syncJobRepository;
    private final ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService;
    private final ExternalUpdateConvergenceService externalUpdateConvergenceService;
    private final boolean acceptAmbiguous;

    public ProviderEventProjectionService(ProviderEventProjectionRepository repository,
                                          ProviderEventVersionComparator comparator,
                                          MeterRegistry meterRegistry,
                                          PlatformTransactionManager transactionManager,
                                          SyncInvariantMonitor invariantMonitor,
                                          CalendarEventMappingRepository mappingRepository,
                                          CalendarSyncJobRepository syncJobRepository,
                                          ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService,
                                          ExternalUpdateConvergenceService externalUpdateConvergenceService,
                                          @Value("${sync.projection.accept-ambiguous:true}") boolean acceptAmbiguous) {
        this.repository = repository;
        this.comparator = comparator;
        this.meterRegistry = meterRegistry;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.invariantMonitor = invariantMonitor;
        this.mappingRepository = mappingRepository;
        this.syncJobRepository = syncJobRepository;
        this.terminalDeleteConvergenceService = terminalDeleteConvergenceService;
        this.externalUpdateConvergenceService = externalUpdateConvergenceService;
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
            } catch (DuplicateKeyException ex) {
                counter("sync.projection.concurrent_retry.total", provider, "reason", "unique_conflict").increment();
                if (attempt == MAX_ATTEMPTS) {
                    counter("sync.projection.retry.exhausted.total", provider, "reason", "unique_conflict").increment();
                    throw ex;
                }
            } catch (DataIntegrityViolationException ex) {
                // Non-retriable integrity violations (e.g. check-constraint failures with SQLState 23514).
                // Surface immediately so the root cause is not masked by retries on the same poisoned row.
                counter("sync.projection.constraint_violation.total", provider, "reason", "non_retriable").increment();
                throw ex;
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
            BookingLinkResolution linkResolution = resolveBookingId(connectionId, provider, incoming.externalEventId(), existing);
            UUID linkedBookingId = linkResolution.bookingId();
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
            if (existing.isPresent() && isProjectionEcho(existing.get(), incoming)) {
                meterRegistry.counter("convergence_dedup_applied",
                        "provider", provider,
                        "connectionId", connectionId.toString(),
                        "reason", "projection_echo").increment();
                meterRegistry.counter("provider_projection_echo_detected",
                        "provider", provider,
                        "connectionId", connectionId.toString()).increment();
                log.info("convergence_loop_prevented provider={} connectionId={} externalEventId={} bookingId={} reason=projection_echo",
                        provider, connectionId, incoming.externalEventId(), existing.get().getBookingId());
                log.info("replay_window_duplicate_suppressed provider={} connectionId={} externalEventId={} bookingId={} reason=projection_echo payloadHash={}",
                        provider, connectionId, incoming.externalEventId(), existing.get().getBookingId(), incoming.payloadHash());
                return false;
            }

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
                // For externally-created (unlinked) events, allow the calendar_events write through
                // even when the projection version hasn't advanced. The projection row is not updated,
                // but the calendar_events upsert is idempotent and self-heals any gap caused by a
                // prior transaction split between the projection commit and the calendar_events write.
                if (linkedBookingId == null && existing.isPresent()) {
                    counter("sync.projection.external_stable_passthrough.total", provider, "reason", "unlinked_idempotent").increment();
                    log.info("projection_apply_result provider={} connectionId={} externalEventId={} applied=true reason=external_stable_passthrough",
                            provider, connectionId, incoming.externalEventId());
                    return true;
                }
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
                    && ProviderEventProjectionStatus.TOMBSTONED_HARD.name().equals(existing.get().getProjectionStatus())
                    && !incoming.cancelled() && !incoming.deleted()) {
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
            projection.setProjectionStatus(projectionStatusFor(incoming));
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
            if (incoming.deleted()) {
                log.info("projection_removed provider={} connectionId={} externalEventId={} projectionStatus={}",
                        provider, connectionId, incoming.externalEventId(), projection.getProjectionStatus());
            }
            if (incoming.cancelled() || incoming.deleted()) {
                if (linkedBookingId == null) {
                    if (linkResolution.classification() == LinkageClassification.BENIGN_UNMANAGED) {
                        meterRegistry.counter("sync.projection.unmanaged_external.total",
                                "provider", provider,
                                "reason", linkResolution.reason()).increment();
                        log.info("provider_event_unmanaged_external provider={} connectionId={} externalEventId={} reason={} matches={}",
                                provider, connectionId, incoming.externalEventId(), linkResolution.reason(), linkResolution.matches());
                    } else {
                        meterRegistry.counter("sync.projection.linkage_risk.total",
                                "provider", provider,
                                "reason", linkResolution.reason()).increment();
                        log.warn("provider_tombstone_booking_linkage_failed provider={} connectionId={} externalEventId={} reason=no_deterministic_booking linkageReason={} matches={}",
                                provider, connectionId, incoming.externalEventId(), linkResolution.reason(), linkResolution.matches());
                    }
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
            } else if (linkedBookingId != null) {
                var updateResult = externalUpdateConvergenceService.convergeProviderUpdate(
                        linkedBookingId,
                        provider,
                        incoming.externalEventId(),
                        incoming.startsAt(),
                        incoming.endsAt(),
                        "provider_projection");
                log.info("provider_active_update_convergence provider={} connectionId={} bookingId={} externalEventId={} result={} bookingRows={}",
                        provider,
                        connectionId,
                        linkedBookingId,
                        incoming.externalEventId(),
                        updateResult.result(),
                        updateResult.bookingRows());
            }
            invariantMonitor.assertState(
                    "projection_mutation",
                    (incoming.cancelled() || incoming.deleted()) ? BookingState.CANCELLED : BookingState.CONFIRMED,
                    SyncJobStatus.SYNCED,
                    (incoming.cancelled() || incoming.deleted())
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

    private BookingLinkResolution resolveBookingId(UUID connectionId,
                                                   String provider,
                                                   String externalEventId,
                                                   Optional<ProviderEventProjection> existing) {
        if (existing.isPresent() && existing.get().getBookingId() != null) {
            counter("sync.projection.booking_linkage.total", provider, "result", "existing").increment();
            return BookingLinkResolution.linked(existing.get().getBookingId(), "existing", 1);
        }
        var syncCandidates = syncJobRepository.findBookingCandidatesForExternalEvent(connectionId, provider, externalEventId);
        if (syncCandidates.size() == 1) {
            UUID bookingId = syncCandidates.get(0).getBookingId();
            counter("sync.projection.booking_linkage.total", provider, "result", "linked_sync_job").increment();
            log.info("provider_event_booking_linked provider={} connectionId={} externalEventId={} bookingId={} reason=sync_job",
                    provider, connectionId, externalEventId, bookingId);
            return BookingLinkResolution.linked(bookingId, "linked_sync_job", 1);
        }
        if (syncCandidates.size() > 1) {
            counter("sync.projection.booking_linkage.total", provider, "result", "ambiguous_sync_job").increment();
            log.warn("provider_event_booking_ambiguous provider={} connectionId={} externalEventId={} reason=sync_job matches={}",
                    provider, connectionId, externalEventId, syncCandidates.size());
            return BookingLinkResolution.unlinked("ambiguous_sync_job", syncCandidates.size(), LinkageClassification.RISKY_AMBIGUOUS);
        }
        CalendarEventMappingRepository.BookingLinkageResult linkage =
                mappingRepository.findUniqueBookingForProviderEvent(connectionId, provider, externalEventId);
        if (linkage.bookingId().isPresent()) {
            counter("sync.projection.booking_linkage.total", provider, "result", "linked").increment();
            log.info("provider_event_booking_linked provider={} connectionId={} externalEventId={} bookingId={} reason={} matches={}",
                    provider, connectionId, externalEventId, linkage.bookingId().get(), linkage.reason(), linkage.matches());
            return BookingLinkResolution.linked(linkage.bookingId().get(), linkage.reason(), linkage.matches());
        }
        counter("sync.projection.booking_linkage.total", provider, "result", linkage.reason()).increment();
        log.info("provider_event_booking_linkage_absent provider={} connectionId={} externalEventId={} reason={} matches={}",
                provider, connectionId, externalEventId, linkage.reason(), linkage.matches());
        LinkageClassification classification = "ambiguous".equals(linkage.reason())
                ? LinkageClassification.RISKY_AMBIGUOUS
                : LinkageClassification.BENIGN_UNMANAGED;
        return BookingLinkResolution.unlinked(linkage.reason(), linkage.matches(), classification);
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

    private static boolean isProjectionEcho(ProviderEventProjection existing,
                                            CalendarEventIngestionService.IncomingCalendarEvent incoming) {
        // Only suppress as a projection echo when the existing row is linked to a BunnyCal booking.
        // An unlinked row means the event was created externally (e.g. a manual Outlook busy block).
        // Suppressing unlinked events would permanently hide externally-created provider events after
        // their first ingest, because the payload hash never changes for stable unchanged events.
        if (existing.getBookingId() == null) {
            return false;
        }
        String existingHash = existing.getPayloadHash();
        String incomingHash = incoming.payloadHash();
        if (existingHash != null && !existingHash.isBlank() && incomingHash != null && !incomingHash.isBlank()) {
            return existingHash.equals(incomingHash);
        }
        boolean sameWindow = existing.getProviderUpdatedAt() != null
                && incoming.providerUpdatedAt() != null
                && existing.getProviderUpdatedAt().equals(incoming.providerUpdatedAt());
        boolean incomingTerminal = incoming.cancelled() || incoming.deleted();
        String existingStatus = existing.getProjectionStatus();
        boolean existingTerminal = ProviderEventProjectionStatus.TOMBSTONED_SOFT.name().equals(existingStatus)
                || ProviderEventProjectionStatus.TOMBSTONED_HARD.name().equals(existingStatus);
        return sameWindow && existingTerminal == incomingTerminal;
    }

    private static String projectionStatusFor(CalendarEventIngestionService.IncomingCalendarEvent incoming) {
        if (incoming.deleted()) return ProviderEventProjectionStatus.TOMBSTONED_HARD.name();
        if (incoming.cancelled()) return ProviderEventProjectionStatus.TOMBSTONED_SOFT.name();
        return ProviderEventProjectionStatus.ACTIVE.name();
    }

    private enum LinkageClassification {
        BENIGN_UNMANAGED,
        RISKY_AMBIGUOUS
    }

    private record BookingLinkResolution(UUID bookingId,
                                         String reason,
                                         int matches,
                                         LinkageClassification classification) {
        private static BookingLinkResolution linked(UUID bookingId, String reason, int matches) {
            return new BookingLinkResolution(bookingId, reason, matches, LinkageClassification.BENIGN_UNMANAGED);
        }

        private static BookingLinkResolution unlinked(String reason, int matches, LinkageClassification classification) {
            return new BookingLinkResolution(null, reason, matches, classification);
        }
    }
}
