package io.bunnycal.conferencing.service;

import java.time.Instant;
import java.util.Map;

public record ConferenceDetails(
        String provider,
        String joinUrl,
        String dialIn,
        String meetingCode,
        String password,
        Map<String, Object> rawPayload,
        String sourceOfTruth,
        Instant updatedAt) {

    public static ConferenceDetails none(String sourceOfTruth, Instant updatedAt) {
        return new ConferenceDetails(
                "NONE",
                null,
                null,
                null,
                null,
                Map.of(),
                sourceOfTruth,
                updatedAt == null ? Instant.now() : updatedAt
        );
    }

    public static ConferenceDetails fromInstruction(ConferencingInstruction instruction,
                                                    String sourceOfTruth,
                                                    Instant updatedAt) {
        if (instruction == null || instruction.providerType() == null) {
            return none(sourceOfTruth, updatedAt);
        }
        return new ConferenceDetails(
                instruction.providerType().name(),
                blankToNull(instruction.joinUrl()),
                null,
                blankToNull(instruction.meetingId()),
                null,
                Map.of(
                        "mode", String.valueOf(instruction.mode()),
                        "hostUrl", orEmpty(instruction.hostUrl())
                ),
                sourceOfTruth,
                updatedAt == null ? Instant.now() : updatedAt
        );
    }

    public ConferenceDetails withJoinUrlIfMissing(String value, String source) {
        String normalized = blankToNull(value);
        if (this.joinUrl != null || normalized == null) {
            return this;
        }
        return new ConferenceDetails(
                provider,
                normalized,
                dialIn,
                meetingCode,
                password,
                rawPayload,
                source,
                Instant.now());
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
