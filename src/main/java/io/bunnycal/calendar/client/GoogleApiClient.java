package io.bunnycal.calendar.client;

import io.bunnycal.calendar.provider.CreateEventRequest;
import io.bunnycal.calendar.provider.UpdateEventRequest;
import java.time.Instant;
import java.util.List;

public interface GoogleApiClient {
    GoogleEventDetails createEvent(String accessToken, CreateEventRequest request);

    GoogleEventDetails updateEvent(String accessToken, UpdateEventRequest request);

    void deleteEvent(String accessToken, String externalEventId);

    boolean eventExists(String accessToken, String externalEventId);

    TokenRefreshResult refreshAccessToken(String refreshToken);

    OAuthTokenExchangeResult exchangeCodeForToken(String code, String redirectUri, String clientId, String clientSecret);

    String fetchProviderUserId(String accessToken);

    List<ProviderCalendarInventoryEntry> listCalendars(String accessToken);

    List<BusyInterval> fetchBusyIntervals(String accessToken, Instant start, Instant end);

    SyncWindow listEventsFull(String accessToken);

    SyncWindow listEventsIncremental(String accessToken, String syncCursor);

    WatchChannel watchEvents(String accessToken, String webhookUrl, String channelToken);

    void stopWatchChannel(String accessToken, String channelId, String resourceId);

    void revokeToken(String token);

    record GoogleEventDetails(String externalEventId, String providerEventUrl, String conferenceUrl) {}

    record BusyInterval(Instant start, Instant end) {}

    record SyncWindow(List<CalendarEventObservation> events, String nextSyncToken) {}

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

    record WatchChannel(String channelId, String resourceId, Instant expiration) {}
}
