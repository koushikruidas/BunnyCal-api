package io.bunnycal.calendar.auth;

import io.bunnycal.calendar.client.MicrosoftApiClient;
import io.bunnycal.calendar.client.TokenRefreshResult;
import io.bunnycal.calendar.domain.CalendarProviderType;
import org.springframework.stereotype.Component;

@Component
public class MicrosoftCalendarTokenClient implements CalendarTokenClient {
    private final MicrosoftApiClient microsoftApiClient;

    public MicrosoftCalendarTokenClient(MicrosoftApiClient microsoftApiClient) {
        this.microsoftApiClient = microsoftApiClient;
    }

    @Override
    public CalendarProviderType provider() {
        return CalendarProviderType.MICROSOFT;
    }

    @Override
    public TokenRefreshResult refreshAccessToken(String refreshToken) {
        return microsoftApiClient.refreshAccessToken(refreshToken);
    }

    @Override
    public String fetchProviderUserId(String accessToken) {
        return microsoftApiClient.fetchProviderUserId(accessToken);
    }
}
