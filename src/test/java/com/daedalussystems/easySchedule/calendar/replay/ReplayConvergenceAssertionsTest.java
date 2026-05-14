package com.daedalussystems.easySchedule.calendar.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayConvergenceAssertionsTest {

    @Test
    void convergedReport_passesAssertions() {
        WebhookReplayReport report = new WebhookReplayReport(
                12L, 6L, 2L, 4L, 6L, 2L, 1L, 1L, 0L, 0L, 6L, 1L, "CANCELLED", "digest");

        ReplayConvergenceAssertions.AssertionResult result = ReplayConvergenceAssertions.assertConverged(report);
        assertTrue(result.converged());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void invariantViolation_failsAssertions() {
        WebhookReplayReport report = new WebhookReplayReport(
                10L, 6L, 2L, 4L, 6L, 2L, 1L, 1L, 0L, 2L, 6L, 1L, "CANCELLED", "digest");

        ReplayConvergenceAssertions.AssertionResult result = ReplayConvergenceAssertions.assertConverged(report);
        assertFalse(result.converged());
        assertTrue(result.violations().contains("invariant_violation_detected"));
    }
}
