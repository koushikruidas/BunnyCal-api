package com.daedalussystems.easySchedule.calendar.auth;

import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import com.daedalussystems.easySchedule.calendar.client.TokenRefreshResult;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.calendar.service.CalendarConnectionWriteService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TokenRefresher {
    private static final Logger log = LoggerFactory.getLogger(TokenRefresher.class);
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(5);
    private static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(2);

    private final CalendarConnectionRepository connectionRepository;
    private final TokenCipher tokenCipher;
    private final GoogleApiClient googleApiClient;
    private final CalendarConnectionWriteService connectionWriteService;
    private final AccessTokenCache accessTokenCache;
    private final Counter tokenRefreshFailureCount;
    private final Counter tokenRefreshSuccessCount;

    public TokenRefresher(CalendarConnectionRepository connectionRepository,
                          TokenCipher tokenCipher,
                          GoogleApiClient googleApiClient,
                          CalendarConnectionWriteService connectionWriteService,
                          AccessTokenCache accessTokenCache,
                          MeterRegistry meterRegistry) {
        this.connectionRepository = connectionRepository;
        this.tokenCipher = tokenCipher;
        this.googleApiClient = googleApiClient;
        this.connectionWriteService = connectionWriteService;
        this.accessTokenCache = accessTokenCache;
        this.tokenRefreshFailureCount = meterRegistry.counter("token_refresh_failures_count");
        this.tokenRefreshSuccessCount = meterRegistry.counter("token_refresh_success_count");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T executeWithValidToken(UUID connectionId, Function<String, T> operation) {
        CalendarConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Calendar connection not found"));

        if (connection.getStatus() == CalendarConnectionStatus.REVOKED) {
            throw new IllegalStateException("Calendar connection revoked");
        }
        if (connection.getStatus() == CalendarConnectionStatus.ERROR
                && connection.getLastErrorAt() != null
                && connection.getLastErrorAt().plus(FAILURE_COOLDOWN).isAfter(Instant.now())) {
            throw new IllegalStateException("Calendar connection temporarily unavailable");
        }

        String token = ensureActiveAccessToken(connection);

        try {
            return operation.apply(token);
        } catch (CalendarClientException ex) {
            if (!ex.isUnauthorized()) {
                throw ex;
            }
            String refreshedToken = refreshConnectionToken(connection);
            try {
                return operation.apply(refreshedToken);
            } catch (CalendarClientException second) {
                if (second.isUnauthorized()) {
                    markFailed(connection, "unauthorized", true);
                }
                throw second;
            }
        }
    }

    private String ensureActiveAccessToken(CalendarConnection connection) {
        Instant now = Instant.now();
        if (connection.getLastTokenExpiresAt() == null || connection.getLastTokenExpiresAt().minus(REFRESH_SKEW).isBefore(now)) {
            return refreshConnectionToken(connection);
        }
        AccessTokenCache.CachedToken cached = accessTokenCache.get(connection.getId()).orElse(null);
        if (cached == null || cached.expiresAt().minus(REFRESH_SKEW).isBefore(now)) {
            return refreshConnectionToken(connection);
        }
        return cached.accessToken();
    }

    private String refreshConnectionToken(CalendarConnection connection) {
        String refreshToken = tokenCipher.decrypt(connection.getRefreshTokenCiphertext());
        try {
            TokenRefreshResult refresh = googleApiClient.refreshAccessToken(refreshToken);
            String token = saveRefreshedToken(connection.getId(), refresh.accessToken(), refresh.expiresAt(), connection.getLastSyncedAt());
            accessTokenCache.put(connection.getId(), token, refresh.expiresAt());
            tokenRefreshSuccessCount.increment();
            log.info("{{\"event\":\"token_refresh_success\",\"connectionId\":\"{}\"}}", connection.getId());
            return token;
        } catch (RuntimeException ex) {
            tokenRefreshFailureCount.increment();
            log.warn("{{\"event\":\"token_refresh_failure\",\"connectionId\":\"{}\"}}", connection.getId(), ex);
            markFailed(connection, resolveErrorCode(ex), isRevokedError(ex));
            throw ex;
        }
    }

    private void markFailed(CalendarConnection connection, String errorCode, boolean revoked) {
        accessTokenCache.remove(connection.getId());
        connectionWriteService.markFailure(connection.getId(),
                revoked ? CalendarConnectionStatus.REVOKED : CalendarConnectionStatus.ERROR,
                errorCode,
                Instant.now(),
                "token_refresh_mark_failed");
    }

    private String saveRefreshedToken(UUID connectionId,
                                      String refreshedAccessToken,
                                      Instant refreshedExpiresAt,
                                      Instant lastSyncedAt) {
        connectionWriteService.markActive(
                connectionId,
                refreshedExpiresAt,
                lastSyncedAt,
                "token_refresh_success");
        return refreshedAccessToken;
    }

    private static String resolveErrorCode(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("invalid_grant")) {
            return "invalid_grant";
        }
        return ex.getClass().getSimpleName();
    }

    private static boolean isRevokedError(RuntimeException ex) {
        String message = ex.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("invalid_grant");
    }
}
