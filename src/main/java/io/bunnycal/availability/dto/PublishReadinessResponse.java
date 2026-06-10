package io.bunnycal.availability.dto;

import java.util.List;

public record PublishReadinessResponse(
        boolean publishable,
        int totalParticipants,
        int readyCount,
        List<EventTypeParticipantResponse> participants) {}
