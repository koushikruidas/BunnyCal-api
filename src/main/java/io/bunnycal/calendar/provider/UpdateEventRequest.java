package io.bunnycal.calendar.provider;

import io.bunnycal.calendar.provider.CreateEventRequest.MultiAttendee;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.time.Instant;
import java.util.List;
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
                                 List<MultiAttendee> attendees,
                                 String targetCalendarId,
                                 ConferencingInstruction conferencingInstruction) {

    public UpdateEventRequest {
        if (conferencingInstruction == null) {
            conferencingInstruction = ConferencingInstruction.none();
        }
        if (attendees == null) {
            attendees = List.of();
        }
    }

    // ── Single-attendee constructors (ONE_ON_ONE) ─────────────────────────────

    public UpdateEventRequest(UUID connectionId,
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
        this(connectionId, externalEventId, title, description, startsAt, endsAt, organizerEmail, attendeeEmail, attendeeName,
                List.of(), targetCalendarId, conferencingInstruction);
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
                List.of(), targetCalendarId, ConferencingInstruction.none());
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
                List.of(), null, ConferencingInstruction.none());
    }

    // ── Multi-attendee constructor (GROUP) ────────────────────────────────────

    public static UpdateEventRequest forGroup(UUID connectionId,
                                              String externalEventId,
                                              String title,
                                              String description,
                                              Instant startsAt,
                                              Instant endsAt,
                                              String organizerEmail,
                                              List<MultiAttendee> attendees,
                                              String targetCalendarId,
                                              ConferencingInstruction conferencingInstruction) {
        return new UpdateEventRequest(connectionId, externalEventId, title, description, startsAt, endsAt,
                organizerEmail, null, null, attendees, targetCalendarId, conferencingInstruction);
    }

    public boolean isMultiAttendee() {
        return attendees != null && !attendees.isEmpty();
    }
}
