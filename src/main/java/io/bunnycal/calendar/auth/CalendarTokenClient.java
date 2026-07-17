package io.bunnycal.calendar.auth;

import io.bunnycal.calendar.client.TokenRefreshResult;
import io.bunnycal.calendar.domain.CalendarProviderType;

public interface CalendarTokenClient {
    CalendarProviderType provider();

    TokenRefreshResult refreshAccessToken(String refreshToken);

    /**
     * The provider's stable, opaque subject id for the account this access token speaks for —
     * the same value the OAuth callback keys a connection on. Used to assert that a stored
     * refresh token still belongs to the account its connection row claims.
     */
    String fetchProviderUserId(String accessToken);
}
