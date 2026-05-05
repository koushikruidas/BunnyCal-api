package com.daedalussystems.easySchedule.booking.dto;

import java.time.Instant;

public record PublicRescheduleRequest(
        Instant startTime
) {
}
