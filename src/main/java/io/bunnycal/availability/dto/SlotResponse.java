package io.bunnycal.availability.dto;

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
        List<SlotDto> slots,
        AvailabilityStatus status) {
    public SlotResponse(UUID userId,
                        UUID eventTypeId,
                        LocalDate date,
                        String timezone,
                        long version,
                        Instant generatedAt,
                        boolean degraded,
                        List<SlotDto> slots) {
        this(userId, eventTypeId, date, timezone, version, generatedAt, degraded, slots,
                (slots == null || slots.isEmpty()) ? AvailabilityStatus.NO_SLOTS_AVAILABLE : AvailabilityStatus.AVAILABLE);
    }
}
