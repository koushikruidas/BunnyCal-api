package io.bunnycal.availability.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SlotDebugTrace(
        String requestId,
        UUID eventTypeId,
        CandidateSlot candidateSlot,
        List<String> rejectionReasons,
        List<BusyIntervalContributor> contributingBusyIntervals,
        List<String> providerSources,
        List<String> availabilityRulesApplied,
        String timezoneContext,
        Instant generatedAt) {

    public record CandidateSlot(Instant start, Instant end) {}

    public record BusyIntervalContributor(
            Instant start,
            Instant end,
            String sourceProvider,
            String sourceCalendarId,
            String sourceEventId,
            String normalizationSource,
            Instant ingestionTimestamp) {}
}
