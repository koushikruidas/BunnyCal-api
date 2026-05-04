package com.daedalussystems.easySchedule.calendar.client;

import java.time.Instant;

public record OAuthTokenExchangeResult(
        String accessToken,
        String refreshToken,
        Instant expiresAt
) {
}
