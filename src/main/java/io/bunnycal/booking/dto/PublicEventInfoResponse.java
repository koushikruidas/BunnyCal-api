package io.bunnycal.booking.dto;

import io.bunnycal.availability.domain.EventKind;

public record PublicEventInfoResponse(
        String name,
        long duration,
        String timezone,
        String hostName,
        String hostUsername,
        String description,
        String location,
        String hostAvatarUrl,
        EventKind kind,
        boolean published
) {
}
