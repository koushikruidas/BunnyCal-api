package io.bunnycal.calendar.provider;

import io.bunnycal.conferencing.service.ConferencingInstruction;
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
                                 String targetCalendarId,
                                 ConferencingInstruction conferencingInstruction) {

    public UpdateEventRequest {
        if (conferencingInstruction == null) {
            conferencingInstruction = ConferencingInstruction.none();
        }
    }

    public UpdateEventRequest(UUID connectionId,
                              String externalEventId,
                              String title,
                              String description,
                              Instant startsAt,
                              Instant endsAt,
                              String organizerEmail,
                              String attendeeEmail,
                              String attendeeName,
                              String targetCalendarId) {
        this(connectionId, externalEventId, title, description, startsAt, endsAt, organizerEmail, attendeeEmail, attendeeName,
                targetCalendarId, ConferencingInstruction.none());
    }

    public UpdateEventRequest(UUID connectionId,
                              String externalEventId,
                              String title,
                              String description,
                              Instant startsAt,
                              Instant endsAt,
                              String organizerEmail,
                              String attendeeEmail,
                              String attendeeName) {
        this(connectionId, externalEventId, title, description, startsAt, endsAt, organizerEmail, attendeeEmail, attendeeName,
                null, ConferencingInstruction.none());
    }
}
