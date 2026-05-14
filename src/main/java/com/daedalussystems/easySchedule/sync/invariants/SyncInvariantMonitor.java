package com.daedalussystems.easySchedule.sync.invariants;

import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SyncInvariantMonitor {
    private static final Logger log = LoggerFactory.getLogger(SyncInvariantMonitor.class);

    private final CompositeSyncStateClassifier classifier;
    private final MeterRegistry meterRegistry;
    private final boolean failFast;

    public SyncInvariantMonitor(CompositeSyncStateClassifier classifier,
                                MeterRegistry meterRegistry,
                                @Value("${sync.invariants.fail-fast:false}") boolean failFast) {
        this.classifier = classifier;
        this.meterRegistry = meterRegistry;
        this.failFast = failFast;
    }

    public CompositeSyncStateClassifier.Classification assertState(
            String source,
            BookingState bookingState,
            SyncJobStatus syncStatus,
            CompositeSyncStateClassifier.ProjectionLifecycle projectionLifecycle,
            CompositeSyncStateClassifier.ParticipationLifecycle participationLifecycle,
            LineageContext lineage) {
        CompositeSyncStateClassifier.Classification classification = classifier.classify(
                bookingState, syncStatus, projectionLifecycle, participationLifecycle);

        meterRegistry.counter("sync.invariant.classification.total",
                "source", source,
                "classification", classification.name()).increment();

        if (classification == CompositeSyncStateClassifier.Classification.REPAIR_REQUIRED) {
            meterRegistry.counter("repair_required_transition_total", "source", source).increment();
            log.warn("sync_invariant_repair_required source={} lineage={} bookingState={} syncStatus={} projection={} participation={}",
                    source, lineage.asLogLine(), bookingState, syncStatus, projectionLifecycle, participationLifecycle);
        }

        if (classification == CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT) {
            meterRegistry.counter("illegal_state_detected_total", "source", source).increment();
            meterRegistry.counter("invariant_violation_total", "source", source, "kind", "illegal").increment();
            log.error("sync_invariant_illegal_state source={} lineage={} bookingState={} syncStatus={} projection={} participation={}",
                    source, lineage.asLogLine(), bookingState, syncStatus, projectionLifecycle, participationLifecycle);
            if (failFast) {
                throw new IllegalStateException("Illegal sync composite state: " + source + " " + lineage.asLogLine());
            }
        }

        return classification;
    }
}
