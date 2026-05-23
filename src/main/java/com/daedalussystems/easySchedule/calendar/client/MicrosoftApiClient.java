package com.daedalussystems.easySchedule.calendar.client;

import com.daedalussystems.easySchedule.calendar.provider.CreateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
import java.time.Instant;
import java.util.List;

public interface MicrosoftApiClient {
    MicrosoftEventDetails createEvent(String accessToken, CreateEventRequest request);

    MicrosoftEventDetails updateEvent(String accessToken, UpdateEventRequest request);

    void deleteEvent(String accessToken, String targetCalendarId, String externalEventId);

    boolean eventExists(String accessToken, String targetCalendarId, String externalEventId);

    TokenRefreshResult refreshAccessToken(String refreshToken);

    OAuthTokenExchangeResult exchangeCodeForToken(String code, String redirectUri, String clientId, String clientSecret, String tenantId);

    String fetchProviderUserId(String accessToken);

    List<BusyInterval> fetchBusyIntervals(String accessToken, Instant start, Instant end);

    SyncWindow listEventsFull(String accessToken);

    SyncWindow listEventsIncremental(String accessToken, String deltaCursor);

    void revokeToken(String token);

    WebhookSubscription createEventSubscription(
            String accessToken,
            String notificationUrl,
            String clientState,
            Instant expiresAt);

    WebhookSubscription renewEventSubscription(
            String accessToken,
            String subscriptionId,
            Instant expiresAt);

    void deleteEventSubscription(String accessToken, String subscriptionId);

    record MicrosoftEventDetails(String externalEventId, String providerEventUrl, String conferenceUrl) {}

    record BusyInterval(Instant start, Instant end) {}

    record SyncWindow(List<CalendarEventObservation> events, String nextDeltaCursor) {}

    record CalendarEventObservation(
            String externalEventId,
            Instant startsAt,
            Instant endsAt,
            boolean cancelled,
            Long providerSequence,
            Instant providerUpdatedAt,
            String providerEtag,
            String payloadHash
    ) {}

    record WebhookSubscription(String subscriptionId, String resourceId, Instant expiresAt, String clientState) {}
}
