package io.bunnycal.sync.reconcile;

import org.springframework.stereotype.Component;

@Component
public class ReconcileShadowParityClassifier {

    public ReconcileShadowParity classify(ReconcileDecision legacyDecision, ReconcileDecision shadowDecision) {
        if (legacyDecision == shadowDecision) {
            return ReconcileShadowParity.EXACT_MATCH;
        }
        if (legacyDecision == ReconcileDecision.REQUIRE_REPAIR && shadowDecision == ReconcileDecision.REQUIRE_RESYNC) {
            return ReconcileShadowParity.ACCEPTABLE_STRICTER;
        }
        if (legacyDecision == ReconcileDecision.NO_ACTION
                && (shadowDecision == ReconcileDecision.REQUIRE_REPAIR
                || shadowDecision == ReconcileDecision.REQUIRE_RESYNC
                || shadowDecision == ReconcileDecision.REQUIRE_MANUAL_REVIEW)) {
            return ReconcileShadowParity.SAFETY_IMPROVEMENT;
        }
        if ((legacyDecision == ReconcileDecision.REQUIRE_REPAIR
                || legacyDecision == ReconcileDecision.REQUIRE_RESYNC)
                && shadowDecision == ReconcileDecision.NO_ACTION) {
            return ReconcileShadowParity.LEGACY_PERMISSIVE;
        }
        return ReconcileShadowParity.OPERATIONAL_DIVERGENCE;
    }
}
