package io.bunnycal.calendar.auth;

import io.bunnycal.calendar.client.GoogleApiClient;
import io.bunnycal.calendar.client.TokenRefreshResult;
import io.bunnycal.calendar.domain.CalendarProviderType;
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
