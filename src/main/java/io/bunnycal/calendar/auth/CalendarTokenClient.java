package io.bunnycal.calendar.auth;

import io.bunnycal.calendar.client.TokenRefreshResult;
import io.bunnycal.calendar.domain.CalendarProviderType;

public interface CalendarTokenClient {
    CalendarProviderType provider();

    TokenRefreshResult refreshAccessToken(String refreshToken);
}
