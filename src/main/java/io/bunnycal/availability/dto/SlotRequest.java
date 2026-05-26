package io.bunnycal.availability.dto;

import java.time.LocalDate;
import java.util.UUID;

public record SlotRequest(UUID userId, UUID eventTypeId, LocalDate date, boolean debug, String requestId) {
    public SlotRequest(UUID userId, UUID eventTypeId, LocalDate date) {
        this(userId, eventTypeId, date, false, null);
    }
}
