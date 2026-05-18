package com.daedalussystems.easySchedule.calendar.provider;

import java.time.Instant;
import java.util.UUID;

public record UpdateEventRequest(UUID connectionId,
                                 String externalEventId,
                                 String title,
                                 String description,
                                 Instant startsAt,
                                 Instant endsAt,
                                 String organizerEmail,
                                 String attendeeEmail,
                                 String attendeeName,
                                 String targetCalendarId) {
    public UpdateEventRequest(UUID connectionId,
                              String externalEventId,
                              String title,
                              String description,
                              Instant startsAt,
                              Instant endsAt,
                              String organizerEmail,
                              String attendeeEmail,
                              String attendeeName) {
        this(connectionId, externalEventId, title, description, startsAt, endsAt, organizerEmail, attendeeEmail, attendeeName, null);
    }
}
