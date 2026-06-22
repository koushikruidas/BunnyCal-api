package io.bunnycal.calendar.provider;

public interface CalendarProvider {
    /*
     * Provider calendar events are organizer-owned projection mirrors only.
     * BunnyCal remains the attendee invitation authority and sends all ICS
     * requests, updates, cancellations, and RSVP semantics itself.
     */
    CreateEventResponse createEvent(CreateEventRequest request);

    UpdateEventResponse updateEvent(UpdateEventRequest request);

    void deleteEvent(DeleteEventRequest request);
}
