package io.bunnycal.team.dto;

import java.util.List;

/**
 * Outcome of a bulk invite. Addresses are processed independently: a duplicate or
 * already-member address lands in {@code failed} without preventing the rest from
 * being invited.
 */
public record BulkInviteMembersResponse(
        List<TeamInvitationResponse> sent,
        List<FailedInvite> failed) {

    /** {@code code} is the machine-readable ErrorCode name; {@code reason} is user-facing. */
    public record FailedInvite(String email, String code, String reason) {}
}
