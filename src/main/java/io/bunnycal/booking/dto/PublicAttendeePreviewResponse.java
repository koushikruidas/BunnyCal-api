package io.bunnycal.booking.dto;

public record PublicAttendeePreviewResponse(
        String displayName,
        String initials,
        String avatarUrl
) {
}
