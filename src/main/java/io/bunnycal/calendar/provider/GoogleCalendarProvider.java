package io.bunnycal.calendar.provider;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.GoogleApiClient;
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
        var details = tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> googleApiClient.createEvent(token, request)
        );
        return new CreateEventResponse(details.externalEventId(), details.providerEventUrl(), details.conferenceUrl());
    }

    @Override
    public UpdateEventResponse updateEvent(UpdateEventRequest request) {
        var details = tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> googleApiClient.updateEvent(token, request)
        );
        return new UpdateEventResponse(details.externalEventId(), details.providerEventUrl(), details.conferenceUrl());
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

    public boolean eventExists(DeleteEventRequest request) {
        return tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> googleApiClient.eventExists(token, request.externalEventId())
        );
    }
}
