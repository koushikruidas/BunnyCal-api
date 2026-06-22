package io.bunnycal.availability.dto;

import java.util.List;

public record PublishReadinessResponse(
        boolean published,
        boolean publishable,
        boolean degraded,
        List<String> reasons,
        int totalParticipants,
        int readyCount,
        List<EventTypeParticipantResponse> participants) {}
