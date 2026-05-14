package com.daedalussystems.easySchedule.sync.invariants;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class InvariantOperationalReactorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final LineageContext lineage = new LineageContext("c1", "ca1", "b1", "e1", "5", "9");

    @Test
    void legalSteady_operationalOnly_noRepairOrIllegalAlerts() {
        InvariantOperationalReactor reactor = new InvariantOperationalReactor(meterRegistry, false);
        CompositeInvariantEvaluator.InvariantEvaluation steady = eval(
                CompositeSyncStateClassifier.Classification.LEGAL_STEADY,
                "booking-service",
                "confirmed_synced_active_happy_path",
                "structural");

        reactor.react("steady_source", steady, lineage);

        assertEquals(1.0, meterRegistry.get("sync.invariant.classification.total")
                .tag("source", "steady_source")
                .tag("classification", "LEGAL_STEADY")
                .counter().count());
        assertNull(meterRegistry.find("repair_required_transition_total").counter());
        assertNull(meterRegistry.find("illegal_state_detected_total").counter());
        assertEquals(CompositeSyncStateClassifier.Classification.LEGAL_STEADY, steady.classification());
    }

    @Test
    void legalTransient_operationalOnly_noRepairOrIllegalAlerts() {
        InvariantOperationalReactor reactor = new InvariantOperationalReactor(meterRegistry, false);
        CompositeInvariantEvaluator.InvariantEvaluation transientEval = eval(
                CompositeSyncStateClassifier.Classification.LEGAL_TRANSIENT,
                "sync-orchestrator",
                "sync_pipeline_in_progress",
                "operational");

        reactor.react("transient_source", transientEval, lineage);

        assertEquals(1.0, meterRegistry.get("sync.invariant.classification.total")
                .tag("source", "transient_source")
                .tag("classification", "LEGAL_TRANSIENT")
                .counter().count());
        assertNull(meterRegistry.find("repair_required_transition_total").counter());
        assertNull(meterRegistry.find("illegal_state_detected_total").counter());
    }

    @Test
    void repairRequired_emitsRepairMetricsAndWarning_withRationaleAndLineage() {
        InvariantOperationalReactor reactor = new InvariantOperationalReactor(meterRegistry, false);
        CompositeInvariantEvaluator.InvariantEvaluation repair = eval(
                CompositeSyncStateClassifier.Classification.REPAIR_REQUIRED,
                "reconcile-engine",
                "terminal_booking_cannot_remain_externally_active",
                "structural");
        ListAppender<ILoggingEvent> appender = attachLogAppender();

        reactor.react("webhook_ingestion", repair, lineage);

        assertEquals(1.0, meterRegistry.get("sync.invariant.classification.total")
                .tag("source", "webhook_ingestion")
                .tag("classification", "REPAIR_REQUIRED")
                .counter().count());
        assertEquals(1.0, meterRegistry.get("repair_required_transition_total")
                .tag("source", "webhook_ingestion")
                .counter().count());

        assertTrue(appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("sync_invariant_repair_required")
                        && e.getFormattedMessage().contains("terminal_booking_cannot_remain_externally_active")
                        && e.getFormattedMessage().contains("correlation_id=c1")));
    }

    @Test
    void illegalAlert_failFastDisabled_emitsAlertMetricsAndError_withoutThrowing() {
        InvariantOperationalReactor reactor = new InvariantOperationalReactor(meterRegistry, false);
        CompositeInvariantEvaluator.InvariantEvaluation illegal = eval(
                CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT,
                "sync-invariants",
                "explicitly_reviewed_illegal_state_combination",
                "structural");
        ListAppender<ILoggingEvent> appender = attachLogAppender();

        assertDoesNotThrow(() -> reactor.react("reconcile", illegal, lineage));

        assertEquals(1.0, meterRegistry.get("sync.invariant.classification.total")
                .tag("source", "reconcile")
                .tag("classification", "ILLEGAL_ALERT")
                .counter().count());
        assertEquals(1.0, meterRegistry.get("illegal_state_detected_total")
                .tag("source", "reconcile")
                .counter().count());
        assertEquals(1.0, meterRegistry.get("invariant_violation_total")
                .tag("source", "reconcile")
                .tag("kind", "illegal")
                .counter().count());

        assertTrue(appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("sync_invariant_illegal_state")
                        && e.getFormattedMessage().contains("explicitly_reviewed_illegal_state_combination")
                        && e.getFormattedMessage().contains("correlation_id=c1")));
    }

    @Test
    void illegalAlert_failFastEnabled_throwsAfterOperationalEmission() {
        InvariantOperationalReactor reactor = new InvariantOperationalReactor(meterRegistry, true);
        CompositeInvariantEvaluator.InvariantEvaluation illegal = eval(
                CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT,
                "sync-invariants",
                "explicitly_reviewed_illegal_state_combination",
                "structural");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reactor.react("reconcile", illegal, lineage));
        assertTrue(ex.getMessage().contains("Illegal sync composite state"));

        assertEquals(1.0, meterRegistry.get("illegal_state_detected_total")
                .tag("source", "reconcile")
                .counter().count());
    }

    @Test
    void replaySafety_duplicateEvaluations_keepSemanticResultStable_andOperationallyDeterministic() {
        InvariantOperationalReactor reactor = new InvariantOperationalReactor(meterRegistry, false);
        CompositeInvariantEvaluator.InvariantEvaluation illegal = eval(
                CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT,
                "sync-invariants",
                "explicitly_reviewed_illegal_state_combination",
                "structural");

        reactor.react("sourceA", illegal, lineage);
        reactor.react("sourceA", illegal, lineage);

        assertEquals(CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT, illegal.classification());
        assertEquals(2.0, meterRegistry.get("sync.invariant.classification.total")
                .tag("source", "sourceA")
                .tag("classification", "ILLEGAL_ALERT")
                .counter().count());
        assertEquals(2.0, meterRegistry.get("illegal_state_detected_total")
                .tag("source", "sourceA")
                .counter().count());
    }

    @Test
    void regression_reactorCannotClassifyOrMutateSemantics() {
        InvariantOperationalReactor reactor = new InvariantOperationalReactor(meterRegistry, false);
        CompositeInvariantEvaluator.InvariantEvaluation immutable = eval(
                CompositeSyncStateClassifier.Classification.REPAIR_REQUIRED,
                "ownerX",
                "rationaleX",
                "operational");

        reactor.react("x", immutable, lineage);

        assertEquals("ownerX", immutable.owner());
        assertEquals("rationaleX", immutable.rationale());
        assertEquals("operational", immutable.policyRef());
        assertEquals(CompositeSyncStateClassifier.Classification.REPAIR_REQUIRED, immutable.classification());
    }

    private static CompositeInvariantEvaluator.InvariantEvaluation eval(
            CompositeSyncStateClassifier.Classification classification,
            String owner,
            String rationale,
            String policyRef) {
        return new CompositeInvariantEvaluator.InvariantEvaluation(
                new CompositeSyncStateClassifier.CompositeKey(
                        com.daedalussystems.easySchedule.booking.contract.BookingState.CONFIRMED,
                        com.daedalussystems.easySchedule.sync.state.SyncJobStatus.SYNCED,
                        CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                        CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED),
                classification,
                owner,
                rationale,
                policyRef);
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(InvariantOperationalReactor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
