package io.bunnycal.team.dto;

import java.util.List;

public record TeamReadinessSummaryResponse(
        int totalMembers,
        int ready,
        int needsSetup,
        List<MemberReadinessEntry> members) {

    public record MemberReadinessEntry(
            String userId,
            String userName,
            String userEmail,
            String userProfileImageUrl,
            String readinessStatus,
            boolean hasAvailabilityRules,
            boolean hasActiveCalendar,
            String teamMemberId) {}
}
