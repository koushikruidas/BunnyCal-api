package com.daedalussystems.easySchedule.calendar.provider;

import java.time.Instant;
import java.util.UUID;

public record CreateEventRequest(UUID connectionId,
                                 String title,
                                 String description,
                                 Instant startsAt,
                                 Instant endsAt,
                                 String organizerEmail,
                                 String attendeeEmail,
                                 String attendeeName,
                                 String idempotencyKey,
                                 String targetCalendarId) {
    public CreateEventRequest(UUID connectionId,
                              String title,
                              String description,
                              Instant startsAt,
                              Instant endsAt,
                              String organizerEmail,
                              String attendeeEmail,
                              String attendeeName,
                              String idempotencyKey) {
        this(connectionId, title, description, startsAt, endsAt, organizerEmail, attendeeEmail, attendeeName, idempotencyKey, null);
    }
}
