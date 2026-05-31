package io.bunnycal.calendar.replay;

import java.util.ArrayList;
import java.util.List;

public final class ReplayConvergenceAssertions {

    private ReplayConvergenceAssertions() {
    }

    public static AssertionResult assertConverged(WebhookReplayReport report) {
        List<String> violations = new ArrayList<>();
        if (report.projectionVersion() < 0) {
            violations.add("projection_version_negative");
        }
        if (report.terminalIntentEpoch() < 0) {
            violations.add("terminal_intent_epoch_negative");
        }
        if (report.acceptedCount() + report.staleRejectedCount() + report.resurrectionBlockedCount() + report.duplicateCollapsedCount()
                > report.processedCount()) {
            violations.add("processed_count_accounting_invalid");
        }
        if ("CANCELLED".equals(report.terminalStatus()) && report.resurrectionBlockedCount() < 0) {
            violations.add("anti_resurrection_counter_invalid");
        }
        if (report.invariantViolationCount() > 0) {
            violations.add("invariant_violation_detected");
        }
        return new AssertionResult(violations.isEmpty(), List.copyOf(violations));
    }

    public record AssertionResult(boolean converged, List<String> violations) {}
}
