package com.daedalussystems.easySchedule.calendar.provider;

import com.daedalussystems.easySchedule.calendar.auth.TokenRefresher;
import com.daedalussystems.easySchedule.calendar.client.MicrosoftApiClient;
import org.springframework.stereotype.Component;

@Component
public class MicrosoftCalendarProvider implements CalendarProvider {
    private final MicrosoftApiClient microsoftApiClient;
    private final TokenRefresher tokenRefresher;

    public MicrosoftCalendarProvider(MicrosoftApiClient microsoftApiClient, TokenRefresher tokenRefresher) {
        this.microsoftApiClient = microsoftApiClient;
        this.tokenRefresher = tokenRefresher;
    }

    @Override
    public CreateEventResponse createEvent(CreateEventRequest request) {
        var details = tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> microsoftApiClient.createEvent(token, request)
        );
        return new CreateEventResponse(details.externalEventId(), details.providerEventUrl(), details.conferenceUrl());
    }

    @Override
    public UpdateEventResponse updateEvent(UpdateEventRequest request) {
        var details = tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> microsoftApiClient.updateEvent(token, request)
        );
        return new UpdateEventResponse(details.externalEventId(), details.providerEventUrl(), details.conferenceUrl());
    }

    @Override
    public void deleteEvent(DeleteEventRequest request) {
        tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> {
                    microsoftApiClient.deleteEvent(token, "primary", request.externalEventId());
                    return null;
                }
        );
    }

    public boolean eventExists(DeleteEventRequest request) {
        return tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> microsoftApiClient.eventExists(token, "primary", request.externalEventId())
        );
    }
}
