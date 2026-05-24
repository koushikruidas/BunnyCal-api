package com.daedalussystems.easySchedule.conferencing.service;

public record ConferencingExecutionResult(
        ConferencingInstruction instruction,
        Outcome outcome,
        String reasonCode) {

    public enum Outcome {
        APPLIED,
        DEGRADED,
        LEGACY_PASSTHROUGH
    }

    public static ConferencingExecutionResult applied(ConferencingInstruction instruction) {
        return new ConferencingExecutionResult(
                instruction == null ? ConferencingInstruction.none() : instruction,
                Outcome.APPLIED,
                null);
    }

    public static ConferencingExecutionResult degraded(ConferencingInstruction instruction, String reasonCode) {
        return new ConferencingExecutionResult(
                instruction == null ? ConferencingInstruction.none() : instruction,
                Outcome.DEGRADED,
                reasonCode);
    }

    public static ConferencingExecutionResult legacyPassthrough(ConferencingInstruction instruction, String reasonCode) {
        return new ConferencingExecutionResult(
                instruction == null ? ConferencingInstruction.none() : instruction,
                Outcome.LEGACY_PASSTHROUGH,
                reasonCode);
    }
}
