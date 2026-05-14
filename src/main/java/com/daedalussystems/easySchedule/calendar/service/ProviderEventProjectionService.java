package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.domain.ProviderEventProjection;
import com.daedalussystems.easySchedule.calendar.repository.ProviderEventProjectionRepository;
import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.sync.invariants.CompositeSyncStateClassifier;
import com.daedalussystems.easySchedule.sync.invariants.LineageContext;
import com.daedalussystems.easySchedule.sync.invariants.SyncInvariantMonitor;
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
    private final boolean acceptAmbiguous;

    public ProviderEventProjectionService(ProviderEventProjectionRepository repository,
                                          ProviderEventVersionComparator comparator,
                                          MeterRegistry meterRegistry,
                                          PlatformTransactionManager transactionManager,
                                          SyncInvariantMonitor invariantMonitor,
                                          @Value("${sync.projection.accept-ambiguous:true}") boolean acceptAmbiguous) {
        this.repository = repository;
        this.comparator = comparator;
        this.meterRegistry = meterRegistry;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.invariantMonitor = invariantMonitor;
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
        LineageContext lineage = new LineageContext(
                safeMdc("correlationId"),
                safeMdc("causationId"),
                safeMdc("bookingId"),
                incoming.externalEventId(),
                "",
                safeMdc("terminalIntentEpoch"));
        try {
            Optional<ProviderEventProjection> existing = repository.findWithLockByConnectionIdAndProviderAndExternalEventId(
                    connectionId, provider, incoming.externalEventId());

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
            if (compare == ProviderEventVersionComparator.ComparisonResult.OLDER_OR_EQUAL) {
                counter("sync.projection.rejected.total", provider, "reason", "older_or_equal").increment();
                log.info("projection_observation_rejected provider={} reason=older_or_equal {}",
                        provider, lineage.asLogLine());
                return false;
            }
            if (compare == ProviderEventVersionComparator.ComparisonResult.AMBIGUOUS_NEWER_HINT) {
                if (!acceptAmbiguous) {
                    counter("sync.projection.ambiguous.total", provider, "decision", "rejected").increment();
                    counter("sync.projection.rejected.total", provider, "reason", "ambiguous_rejected").increment();
                    log.info("projection_observation_rejected provider={} reason=ambiguous_disabled {}",
                            provider, lineage.asLogLine());
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
                return false;
            }
            projection.setConnectionId(connectionId);
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
