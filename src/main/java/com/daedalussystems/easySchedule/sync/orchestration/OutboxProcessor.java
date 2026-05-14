package com.daedalussystems.easySchedule.sync.orchestration;

import com.daedalussystems.easySchedule.booking.outbox.OutboxEvent;
import com.daedalussystems.easySchedule.booking.outbox.OutboxEventRepository;
import com.daedalussystems.easySchedule.booking.outbox.OutboxEventStatus;
import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.sync.invariants.CompositeSyncStateClassifier;
import com.daedalussystems.easySchedule.sync.invariants.LineageContext;
import com.daedalussystems.easySchedule.sync.invariants.SyncInvariantMonitor;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class OutboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);

    private final OutboxEventRepository outboxEventRepository;
    private final CalendarSyncJobRepository calendarSyncJobRepository;
    private final BookingOutboxEventRouter eventRouter;
    private final SyncInvariantMonitor invariantMonitor;
    private final Counter outboxProcessedCounter;
    private final Counter outboxFailedCounter;

    public OutboxProcessor(OutboxEventRepository outboxEventRepository,
                           CalendarSyncJobRepository calendarSyncJobRepository,
                           BookingOutboxEventRouter eventRouter,
                           SyncInvariantMonitor invariantMonitor,
                           MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.calendarSyncJobRepository = calendarSyncJobRepository;
        this.eventRouter = eventRouter;
        this.invariantMonitor = invariantMonitor;
        this.outboxProcessedCounter = meterRegistry.counter("sync.outbox.processed.total");
        this.outboxFailedCounter = meterRegistry.counter("sync.outbox.failed.total");
    }

    @Transactional
    public int processBatch(int batchSize, String provider) {
        List<UUID> claimed = outboxEventRepository.claimBookingSyncEvents(Instant.now(), batchSize);
        for (UUID id : claimed) {
            OutboxEvent evt = outboxEventRepository.findById(id).orElse(null);
            if (evt == null) {
                continue;
            }
            MDC.put("correlationId", id.toString());
            MDC.put("provider", provider);
            MDC.put("internalRefId", evt.getAggregateId().toString());
            MDC.put("bookingId", evt.getAggregateId().toString());
            try {
                SyncJobPlan plan = eventRouter.toPlan(evt, provider);
                calendarSyncJobRepository.upsertPendingJob(
                        UUID.randomUUID(),
                        plan.internalRefType().name(),
                        plan.internalRefId(),
                        plan.provider(),
                        plan.desiredAction().name(),
                        null
                );
                evt.setStatus(OutboxEventStatus.PROCESSED);
                evt.setLastError(null);
                BookingState bookingState = plan.desiredAction().name().equals("DELETE")
                        ? BookingState.CANCELLED
                        : BookingState.CONFIRMED;
                invariantMonitor.assertState(
                        "sync_enqueue_transition_outbox_processor",
                        bookingState,
                        SyncJobStatus.PENDING,
                        bookingState == BookingState.CANCELLED
                                ? CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_SOFT
                                : CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                        CompositeSyncStateClassifier.ParticipationLifecycle.NEEDS_ACTION,
                        new LineageContext(
                                String.valueOf(id),
                                String.valueOf(id),
                                String.valueOf(plan.internalRefId()),
                                "",
                                "",
                                ""));
                outboxProcessedCounter.increment();
                log.info("{{\"event\":\"outbox_sync_job_created\",\"internalRefId\":\"{}\",\"provider\":\"{}\",\"correlationId\":\"{}\",\"action\":\"{}\"}}",
                        plan.internalRefId(), plan.provider(), id, plan.desiredAction());
            } catch (RuntimeException ex) {
                evt.setStatus(OutboxEventStatus.RETRYING);
                evt.setAttemptCount(evt.getAttemptCount() + 1);
                evt.setLastError("SYNC_OUTBOX_PROCESS_ERROR");
                outboxFailedCounter.increment();
                log.warn("sync outbox processing failed outboxId={}", id, ex);
            } finally {
                MDC.remove("bookingId");
                MDC.remove("internalRefId");
                MDC.remove("provider");
                MDC.remove("correlationId");
            }
            outboxEventRepository.save(evt);
        }
        return claimed.size();
    }
}
