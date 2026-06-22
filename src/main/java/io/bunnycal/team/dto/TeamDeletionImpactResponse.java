package io.bunnycal.team.dto;

/**
 * Informational summary shown before a team is soft-deleted. Reports only what deletion
 * actually affects: member access and pending invitations. Event types and bookings are
 * intentionally excluded — team deletion does not cascade to them.
 */
public record TeamDeletionImpactResponse(
        long memberCount,
        long pendingInvitationCount) {}
