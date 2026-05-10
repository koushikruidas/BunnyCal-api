package com.daedalussystems.easySchedule.booking.dto;

import java.time.Instant;

public record PublicBookRequest(Instant startTime, String guestEmail, String guestName) {
}
