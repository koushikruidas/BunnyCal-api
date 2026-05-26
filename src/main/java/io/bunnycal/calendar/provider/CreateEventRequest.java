package io.bunnycal.calendar.provider;

import io.bunnycal.conferencing.service.ConferencingInstruction;
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
                                 String targetCalendarId,
                                 ConferencingInstruction conferencingInstruction) {

    public CreateEventRequest {
        if (conferencingInstruction == null) {
            conferencingInstruction = ConferencingInstruction.none();
        }
    }

    public CreateEventRequest(UUID connectionId,
                              String title,
                              String description,
                              Instant startsAt,
                              Instant endsAt,
                              String organizerEmail,
                              String attendeeEmail,
                              String attendeeName,
                              String idempotencyKey,
                              String targetCalendarId) {
        this(connectionId, title, description, startsAt, endsAt, organizerEmail, attendeeEmail, attendeeName, idempotencyKey,
                targetCalendarId, ConferencingInstruction.none());
    }

    public CreateEventRequest(UUID connectionId,
                              String title,
                              String description,
                              Instant startsAt,
                              Instant endsAt,
                              String organizerEmail,
                              String attendeeEmail,
                              String attendeeName,
                              String idempotencyKey) {
        this(connectionId, title, description, startsAt, endsAt, organizerEmail, attendeeEmail, attendeeName, idempotencyKey,
                null, ConferencingInstruction.none());
    }
}
