package com.daedalussystems.easySchedule.sync.invariants;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InvariantOperationalReactor {
    private static final Logger log = LoggerFactory.getLogger(InvariantOperationalReactor.class);

    private final MeterRegistry meterRegistry;
    private final boolean failFast;

    public InvariantOperationalReactor(MeterRegistry meterRegistry,
                                       @Value("${sync.invariants.fail-fast:false}") boolean failFast) {
        this.meterRegistry = meterRegistry;
        this.failFast = failFast;
    }

    public void react(String source,
                      CompositeInvariantEvaluator.InvariantEvaluation evaluation,
                      LineageContext lineage) {
        meterRegistry.counter("sync.invariant.classification.total",
                "source", source,
                "classification", evaluation.classification().name()).increment();

        if (evaluation.classification() == CompositeSyncStateClassifier.Classification.REPAIR_REQUIRED) {
            meterRegistry.counter("repair_required_transition_total", "source", source).increment();
            log.warn("sync_invariant_repair_required source={} owner={} rationale={} lineage={} key={}",
                    source, evaluation.owner(), evaluation.rationale(), lineage.asLogLine(), evaluation.key());
        }

        if (evaluation.classification() == CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT) {
            meterRegistry.counter("illegal_state_detected_total", "source", source).increment();
            meterRegistry.counter("invariant_violation_total", "source", source, "kind", "illegal").increment();
            log.error("sync_invariant_illegal_state source={} owner={} rationale={} lineage={} key={}",
                    source, evaluation.owner(), evaluation.rationale(), lineage.asLogLine(), evaluation.key());
            if (failFast) {
                throw new IllegalStateException("Illegal sync composite state: " + source + " " + lineage.asLogLine());
            }
        }
    }
}

