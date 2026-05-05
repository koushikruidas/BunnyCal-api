package com.daedalussystems.easySchedule.booking.dto;

public record PublicEventInfoResponse(
        String name,
        long duration,
        String timezone,
        String hostName,
        String hostUsername,
        String description,
        String location,
        String hostAvatarUrl
) {
}
