package io.bunnycal.team.notification;

import java.util.UUID;

/**
 * Immutable payload carried in the outbox event for TEAM_INVITATION_CREATED.
 * Captured at the moment of invite so the email can be sent without re-loading
 * the invitation row (which may have changed status by the time the worker runs).
 */
public record TeamInvitationOutboxPayload(
        UUID invitationId,
        UUID teamId,
        String teamName,
        String invitedEmail,
        String inviterName,
        String token,
        String acceptUrl
) {}
