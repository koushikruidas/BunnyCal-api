package com.daedalussystems.easySchedule.calendar.provider;

import com.daedalussystems.easySchedule.calendar.auth.TokenRefresher;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import org.springframework.stereotype.Component;

@Component
public class GoogleCalendarProvider implements CalendarProvider {
    private final GoogleApiClient googleApiClient;
    private final TokenRefresher tokenRefresher;

    public GoogleCalendarProvider(GoogleApiClient googleApiClient,
                                  TokenRefresher tokenRefresher) {
        this.googleApiClient = googleApiClient;
        this.tokenRefresher = tokenRefresher;
    }

    @Override
    public CreateEventResponse createEvent(CreateEventRequest request) {
        String externalId = tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> googleApiClient.createEvent(token, request)
        );
        return new CreateEventResponse(externalId);
    }

    @Override
    public UpdateEventResponse updateEvent(UpdateEventRequest request) {
        String externalId = tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> googleApiClient.updateEvent(token, request)
        );
        return new UpdateEventResponse(externalId);
    }

    @Override
    public void deleteEvent(DeleteEventRequest request) {
        tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> {
                    googleApiClient.deleteEvent(token, request.externalEventId());
                    return null;
                }
        );
    }
}
