package com.daedalussystems.easySchedule.calendar.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccessTokenCache {
    Optional<CachedToken> get(UUID connectionId);

    void put(UUID connectionId, String accessToken, Instant expiresAt);

    void remove(UUID connectionId);

    record CachedToken(String accessToken, Instant expiresAt) {}
}

