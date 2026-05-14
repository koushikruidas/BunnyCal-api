package com.daedalussystems.easySchedule.sync.invariants;

import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import org.springframework.stereotype.Component;

@Component
public class SyncInvariantMonitor {
    private final CompositeInvariantEvaluator evaluator;
    private final InvariantOperationalReactor reactor;

    public SyncInvariantMonitor(CompositeInvariantEvaluator evaluator,
                                InvariantOperationalReactor reactor) {
        this.evaluator = evaluator;
        this.reactor = reactor;
    }

    public CompositeSyncStateClassifier.Classification assertState(
            String source,
            BookingState bookingState,
            SyncJobStatus syncStatus,
            CompositeSyncStateClassifier.ProjectionLifecycle projectionLifecycle,
            CompositeSyncStateClassifier.ParticipationLifecycle participationLifecycle,
            LineageContext lineage) {
        CompositeInvariantEvaluator.InvariantEvaluation evaluation = evaluator.evaluate(
                bookingState, syncStatus, projectionLifecycle, participationLifecycle);
        reactor.react(source, evaluation, lineage);
        return evaluation.classification();
    }
}
