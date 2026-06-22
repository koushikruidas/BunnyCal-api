package io.bunnycal.availability.dto;

import java.time.Instant;
import java.util.List;

public record RoundRobinStatsResponse(
        int totalParticipants,
        int ready,
        int needsSetup,
        List<ParticipantStat> assignmentDistribution) {

    public record ParticipantStat(
            String userId,
            String userName,
            String userEmail,
            String readinessStatus,
            long bookingCount,
            Instant lastAssignedAt) {}
}
