package io.bunnycal.calendar.provider;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.MicrosoftApiClient;
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

    // Both paths address the event under the calendar it was actually written to. They used to
    // pass the literal "primary", which is Google's alias and not a calendar Graph can resolve —
    // every request 400'd. Delete failed silently; observe was classified INVALID_REQUEST ->
    // PERMANENT_FAILURE -> PROVIDER_STATE_ORPHANED, so healthy bookings were marked orphaned and
    // hidden from the host's own grid.

    @Override
    public void deleteEvent(DeleteEventRequest request) {
        tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> {
                    microsoftApiClient.deleteEvent(token, request.targetCalendarId(), request.externalEventId());
                    return null;
                }
        );
    }

    public boolean eventExists(DeleteEventRequest request) {
        return tokenRefresher.executeWithValidToken(
                request.connectionId(),
                token -> microsoftApiClient.eventExists(token, request.targetCalendarId(), request.externalEventId())
        );
    }
}
