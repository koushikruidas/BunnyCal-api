package com.daedalussystems.easySchedule.availability.dto;

import java.time.Instant;

public record SlotDto(String slotId, Instant start, Instant end, boolean available) {
    public SlotDto(String slotId, Instant start, Instant end) {
        this(slotId, start, end, true);
    }
}
