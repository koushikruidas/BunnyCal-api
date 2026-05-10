package com.daedalussystems.easySchedule.calendar.auth;

import java.util.UUID;

public record OAuthStatePayload(
        UUID userId,
        String source,
        String returnTo,
        String bookingSessionId,
        long expiresAtEpochSeconds) {
}
