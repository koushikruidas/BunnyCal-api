package com.daedalussystems.easySchedule.calendar.service;

import java.util.UUID;

public interface CalendarService {
    CreateEventResult createEvent(CreateCalendarEventCommand command);

    String updateEvent(UpdateCalendarEventCommand command);

    void deleteEvent(DeleteCalendarEventCommand command);

    record CreateCalendarEventCommand(UUID internalId,
                                      String provider,
                                      String idempotencyKey) {
    }

    record UpdateCalendarEventCommand(UUID internalId,
                                      String provider,
                                      String externalEventId,
                                      String idempotencyKey) {
    }

    record DeleteCalendarEventCommand(UUID internalId,
                                      String provider,
                                      String externalEventId) {
    }

    enum CreateEventStatus {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    record CreateEventResult(CreateEventStatus status,
                             String externalEventId,
                             String errorCode) {
        public static CreateEventResult success(String externalEventId) {
            return new CreateEventResult(CreateEventStatus.SUCCESS, externalEventId, null);
        }

        public static CreateEventResult retryable(String errorCode) {
            return new CreateEventResult(CreateEventStatus.RETRYABLE_FAILURE, null, errorCode);
        }

        public static CreateEventResult permanent(String errorCode) {
            return new CreateEventResult(CreateEventStatus.PERMANENT_FAILURE, null, errorCode);
        }
    }
}
