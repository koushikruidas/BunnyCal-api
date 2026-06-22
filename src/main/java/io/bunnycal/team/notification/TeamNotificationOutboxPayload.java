package io.bunnycal.team.notification;

import java.util.UUID;

public record TeamNotificationOutboxPayload(
        UUID invitationId,
        UUID teamId,
        String teamName,
        String recipientEmail,
        String recipientName,
        String actorName,
        String token,
        String acceptUrl
) {}
