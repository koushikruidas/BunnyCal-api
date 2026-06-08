package io.bunnycal.availability.dto;

import java.time.Instant;

public record SlotDto(String slotId, Instant start, Instant end, boolean available, String bookingToken) {
    public SlotDto(String slotId, Instant start, Instant end) {
        this(slotId, start, end, true, null);
    }

    public SlotDto(String slotId, Instant start, Instant end, boolean available) {
        this(slotId, start, end, available, null);
    }
}
