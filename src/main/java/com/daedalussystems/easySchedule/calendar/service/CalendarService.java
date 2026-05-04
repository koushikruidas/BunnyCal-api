package com.daedalussystems.easySchedule.calendar.service;

import java.util.UUID;

public interface CalendarService {
    CreateEventResult createEvent(CreateCalendarEventCommand command);

    String updateEvent(UpdateCalendarEventCommand command);

    void deleteEvent(DeleteCalendarEventCommand command);

    ObserveEventResult observeEvent(ObserveEventCommand command);

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

    record ObserveEventCommand(UUID internalId,
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

    enum ObserveEventStatus {
        EXISTS,
        MISSING,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    record ObserveEventResult(ObserveEventStatus status, String errorCode) {
        public static ObserveEventResult exists() {
            return new ObserveEventResult(ObserveEventStatus.EXISTS, null);
        }

        public static ObserveEventResult missing() {
            return new ObserveEventResult(ObserveEventStatus.MISSING, null);
        }

        public static ObserveEventResult retryable(String errorCode) {
            return new ObserveEventResult(ObserveEventStatus.RETRYABLE_FAILURE, errorCode);
        }

        public static ObserveEventResult permanent(String errorCode) {
            return new ObserveEventResult(ObserveEventStatus.PERMANENT_FAILURE, errorCode);
        }
    }
}
