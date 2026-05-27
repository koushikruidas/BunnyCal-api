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

    /**
     * Calendar-scoped variants for multi-calendar Google sync.
     *
     * <p>{@code listEventsFull} bootstraps a fresh {@code syncToken} for the given
     * Google calendar id (the user's primary or any secondary calendar id returned
     * by {@code calendarList.list}). {@code listEventsIncremental} follows a
     * previously-persisted token. Both paginate via {@code nextPageToken} until
     * Google surfaces the terminal {@code nextSyncToken}, and return only the
     * {@code nextSyncToken} in {@link SyncWindow#nextSyncToken()} — never a page
     * token. This mirrors Microsoft's {@code listCalendarViewDelta} contract so
     * the two provider clients can be driven by structurally identical sync code.
     */
    SyncWindow listEventsFull(String accessToken, String externalCalendarId);

    SyncWindow listEventsIncremental(String accessToken, String externalCalendarId, String syncCursor);

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
            String payloadHash,
            String title,
            String location,
            String organizerEmail
    ) {
        public CalendarEventObservation(String externalEventId,
                                        Instant startsAt,
                                        Instant endsAt,
                                        boolean cancelled,
                                        Long providerSequence,
                                        Instant providerUpdatedAt,
                                        String providerEtag,
                                        String payloadHash) {
            this(externalEventId, startsAt, endsAt, cancelled, providerSequence, providerUpdatedAt, providerEtag, payloadHash,
                    null, null, null);
        }
    }

    record WatchChannel(String channelId, String resourceId, Instant expiration) {}
}
