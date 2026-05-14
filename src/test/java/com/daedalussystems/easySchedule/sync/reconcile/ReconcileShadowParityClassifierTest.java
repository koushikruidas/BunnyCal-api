package com.daedalussystems.easySchedule.sync.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReconcileShadowParityClassifierTest {

    private final ReconcileShadowParityClassifier classifier = new ReconcileShadowParityClassifier();

    @Test
    void classify_exactMatch() {
        assertEquals(ReconcileShadowParity.EXACT_MATCH,
                classifier.classify(ReconcileDecision.NO_ACTION, ReconcileDecision.NO_ACTION));
    }

    @Test
    void classify_safetyImprovement() {
        assertEquals(ReconcileShadowParity.SAFETY_IMPROVEMENT,
                classifier.classify(ReconcileDecision.NO_ACTION, ReconcileDecision.REQUIRE_REPAIR));
    }

    @Test
    void classify_acceptableStricter() {
        assertEquals(ReconcileShadowParity.ACCEPTABLE_STRICTER,
                classifier.classify(ReconcileDecision.REQUIRE_REPAIR, ReconcileDecision.REQUIRE_RESYNC));
    }
}
