package com.daedalussystems.easySchedule.availability.dto;

import java.util.UUID;

public record EventTypeSummaryResponse(
        UUID id,
        String name,
        String slug,
        String link
) {
}
