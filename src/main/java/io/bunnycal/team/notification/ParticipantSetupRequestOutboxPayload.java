package io.bunnycal.team.notification;

import java.util.UUID;

public record ParticipantSetupRequestOutboxPayload(
        UUID setupRequestId,
        UUID teamId,
        String teamName,
        String targetEmail,
        String targetName,
        String ownerName,
        String setupUrl) {}
