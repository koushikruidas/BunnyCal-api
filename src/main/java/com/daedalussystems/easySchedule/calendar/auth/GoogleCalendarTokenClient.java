package com.daedalussystems.easySchedule.calendar.auth;

import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import com.daedalussystems.easySchedule.calendar.client.TokenRefreshResult;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import org.springframework.stereotype.Component;

@Component
public class GoogleCalendarTokenClient implements CalendarTokenClient {
    private final GoogleApiClient googleApiClient;

    public GoogleCalendarTokenClient(GoogleApiClient googleApiClient) {
        this.googleApiClient = googleApiClient;
    }

    @Override
    public CalendarProviderType provider() {
        return CalendarProviderType.GOOGLE;
    }

    @Override
    public TokenRefreshResult refreshAccessToken(String refreshToken) {
        return googleApiClient.refreshAccessToken(refreshToken);
    }
}
