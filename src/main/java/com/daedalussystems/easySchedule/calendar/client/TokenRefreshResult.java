package com.daedalussystems.easySchedule.calendar.client;

import java.time.Instant;

public record TokenRefreshResult(String accessToken, Instant expiresAt) {
}
