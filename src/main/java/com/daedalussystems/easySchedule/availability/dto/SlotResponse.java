package com.daedalussystems.easySchedule.availability.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SlotResponse(
        UUID userId,
        UUID eventTypeId,
        LocalDate date,
        String timezone,
        long version,
        Instant generatedAt,
        boolean degraded,
        List<SlotDto> slots) {}
