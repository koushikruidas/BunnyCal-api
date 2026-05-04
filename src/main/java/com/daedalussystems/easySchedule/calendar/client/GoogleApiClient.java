package com.daedalussystems.easySchedule.calendar.client;

import com.daedalussystems.easySchedule.calendar.provider.CreateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;

public interface GoogleApiClient {
    String createEvent(String accessToken, CreateEventRequest request);

    String updateEvent(String accessToken, UpdateEventRequest request);

    void deleteEvent(String accessToken, String externalEventId);

    TokenRefreshResult refreshAccessToken(String refreshToken);

    OAuthTokenExchangeResult exchangeCodeForToken(String code, String redirectUri, String clientId, String clientSecret);

    String fetchProviderUserId(String accessToken);
}
