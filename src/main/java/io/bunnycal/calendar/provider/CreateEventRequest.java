package io.bunnycal.calendar.provider;

import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateEventRequest(UUID connectionId,
                                 String title,
                                 String description,
                                 Instant startsAt,
                                 Instant endsAt,
                                 String organizerEmail,
                                 String attendeeEmail,
                                 String attendeeName,
                                 List<MultiAttendee> attendees,
                                 String idempotencyKey,
                                 String targetCalendarId,
                                 ConferencingInstruction conferencingInstruction) {

    public record MultiAttendee(String email, String name) {}

    public CreateEventRequest {
        if (conferencingInstruction == null) {
            conferencingInstruction = ConferencingInstruction.none();
        }
        if (attendees == null) {
            attendees = List.of();
        }
    }

    // ── Single-attendee constructors (ONE_ON_ONE) ─────────────────────────────

    public CreateEventRequest(UUID connectionId,
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
        this(connectionId, title, description, startsAt, endsAt, organizerEmail, attendeeEmail, attendeeName,
                List.of(), idempotencyKey, targetCalendarId, conferencingInstruction);
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
        this(connectionId, title, description, startsAt, endsAt, organizerEmail, attendeeEmail, attendeeName,
                List.of(), idempotencyKey, targetCalendarId, ConferencingInstruction.none());
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
        this(connectionId, title, description, startsAt, endsAt, organizerEmail, attendeeEmail, attendeeName,
                List.of(), idempotencyKey, null, ConferencingInstruction.none());
    }

    // ── Multi-attendee constructor (GROUP) ────────────────────────────────────

    public static CreateEventRequest forGroup(UUID connectionId,
                                              String title,
                                              String description,
                                              Instant startsAt,
                                              Instant endsAt,
                                              String organizerEmail,
                                              List<MultiAttendee> attendees,
                                              String idempotencyKey,
                                              String targetCalendarId,
                                              ConferencingInstruction conferencingInstruction) {
        return new CreateEventRequest(connectionId, title, description, startsAt, endsAt,
                organizerEmail, null, null, attendees, idempotencyKey, targetCalendarId, conferencingInstruction);
    }

    public boolean isMultiAttendee() {
        return attendees != null && !attendees.isEmpty();
    }
}
