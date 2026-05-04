package com.daedalussystems.easySchedule.calendar.provider;

public interface CalendarProvider {
    CreateEventResponse createEvent(CreateEventRequest request);

    UpdateEventResponse updateEvent(UpdateEventRequest request);

    void deleteEvent(DeleteEventRequest request);
}
