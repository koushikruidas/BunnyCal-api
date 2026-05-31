package io.bunnycal.calendar.client;

import java.time.Instant;

public record OAuthTokenExchangeResult(
        String accessToken,
        String refreshToken,
        Instant expiresAt
) {
}
