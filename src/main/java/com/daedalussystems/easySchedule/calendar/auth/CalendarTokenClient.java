package com.daedalussystems.easySchedule.calendar.auth;

import com.daedalussystems.easySchedule.calendar.client.TokenRefreshResult;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;

public interface CalendarTokenClient {
    CalendarProviderType provider();

    TokenRefreshResult refreshAccessToken(String refreshToken);
}
