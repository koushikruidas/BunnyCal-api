package io.bunnycal.calendar.client;

import java.time.Instant;

public record TokenRefreshResult(String accessToken, Instant expiresAt) {
}
