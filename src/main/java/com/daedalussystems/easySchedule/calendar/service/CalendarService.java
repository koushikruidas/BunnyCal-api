package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.conferencing.service.ConferencingInstruction;
import java.util.UUID;
import org.springframework.lang.Nullable;

public interface CalendarService {
    CreateEventResult createEvent(CreateCalendarEventCommand command);

    String updateEvent(UpdateCalendarEventCommand command);

    void deleteEvent(DeleteCalendarEventCommand command);

    ObserveEventResult observeEvent(ObserveEventCommand command);

    record CreateCalendarEventCommand(UUID internalId,
                                      String provider,
                                      String idempotencyKey,
                                      ConferencingInstruction conferencingInstruction,
                                      @Nullable UUID schedulingConnectionId) {
        public CreateCalendarEventCommand {
            if (conferencingInstruction == null) {
                conferencingInstruction = ConferencingInstruction.none();
            }
        }

        public CreateCalendarEventCommand(UUID internalId, String provider, String idempotencyKey,
                                          ConferencingInstruction conferencingInstruction) {
            this(internalId, provider, idempotencyKey, conferencingInstruction, null);
        }

        public CreateCalendarEventCommand(UUID internalId, String provider, String idempotencyKey) {
            this(internalId, provider, idempotencyKey, ConferencingInstruction.none(), null);
        }
    }

    record UpdateCalendarEventCommand(UUID internalId,
                                      String provider,
                                      String externalEventId,
                                      String idempotencyKey,
                                      ConferencingInstruction conferencingInstruction,
                                      @Nullable UUID schedulingConnectionId) {
        public UpdateCalendarEventCommand {
            if (conferencingInstruction == null) {
                conferencingInstruction = ConferencingInstruction.none();
            }
        }

        public UpdateCalendarEventCommand(UUID internalId, String provider, String externalEventId,
                                          String idempotencyKey, ConferencingInstruction conferencingInstruction) {
            this(internalId, provider, externalEventId, idempotencyKey, conferencingInstruction, null);
        }

        public UpdateCalendarEventCommand(UUID internalId, String provider, String externalEventId, String idempotencyKey) {
            this(internalId, provider, externalEventId, idempotencyKey, ConferencingInstruction.none(), null);
        }
    }

    record DeleteCalendarEventCommand(UUID internalId,
                                      String provider,
                                      String externalEventId,
                                      @Nullable UUID schedulingConnectionId) {
        public DeleteCalendarEventCommand(UUID internalId, String provider, String externalEventId) {
            this(internalId, provider, externalEventId, null);
        }
    }

    record ObserveEventCommand(UUID internalId,
                               String provider,
                               String externalEventId,
                               String idempotencyKey) {
    }

    enum CreateEventStatus {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    record CreateEventResult(CreateEventStatus status,
                             String externalEventId,
                             String providerEventUrl,
                             String conferenceUrl,
                             String errorCode) {
        public static CreateEventResult success(String externalEventId) {
            return new CreateEventResult(CreateEventStatus.SUCCESS, externalEventId, null, null, null);
        }

        public static CreateEventResult success(String externalEventId,
                                                String providerEventUrl,
                                                String conferenceUrl) {
            return new CreateEventResult(CreateEventStatus.SUCCESS, externalEventId, providerEventUrl, conferenceUrl, null);
        }

        public static CreateEventResult retryable(String errorCode) {
            return new CreateEventResult(CreateEventStatus.RETRYABLE_FAILURE, null, null, null, errorCode);
        }

        public static CreateEventResult permanent(String errorCode) {
            return new CreateEventResult(CreateEventStatus.PERMANENT_FAILURE, null, null, null, errorCode);
        }
    }

    enum ObserveEventStatus {
        EXISTS,
        MISSING,
        MISMATCH,
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

        public static ObserveEventResult mismatch() {
            return new ObserveEventResult(ObserveEventStatus.MISMATCH, null);
        }

        public static ObserveEventResult retryable(String errorCode) {
            return new ObserveEventResult(ObserveEventStatus.RETRYABLE_FAILURE, errorCode);
        }

        public static ObserveEventResult permanent(String errorCode) {
            return new ObserveEventResult(ObserveEventStatus.PERMANENT_FAILURE, errorCode);
        }
    }
}
