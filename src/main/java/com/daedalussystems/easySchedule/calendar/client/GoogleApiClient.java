package com.daedalussystems.easySchedule.calendar.client;

import com.daedalussystems.easySchedule.calendar.provider.CreateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
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

    List<BusyInterval> fetchBusyIntervals(String accessToken, Instant start, Instant end);

    record GoogleEventDetails(String externalEventId, String providerEventUrl, String conferenceUrl) {}

    record BusyInterval(Instant start, Instant end) {}
}
