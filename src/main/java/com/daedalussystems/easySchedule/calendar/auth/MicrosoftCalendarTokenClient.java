package com.daedalussystems.easySchedule.calendar.auth;

import com.daedalussystems.easySchedule.calendar.client.MicrosoftApiClient;
import com.daedalussystems.easySchedule.calendar.client.TokenRefreshResult;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
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
}
