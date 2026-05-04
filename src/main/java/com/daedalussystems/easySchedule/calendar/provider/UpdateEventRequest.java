package com.daedalussystems.easySchedule.calendar.provider;

import java.time.Instant;
import java.util.UUID;

public record UpdateEventRequest(UUID connectionId,
                                 String externalEventId,
                                 String title,
                                 String description,
                                 Instant startsAt,
                                 Instant endsAt) {
}
