package com.daedalussystems.easySchedule.calendar.auth;

import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import com.daedalussystems.easySchedule.calendar.client.TokenRefreshResult;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.OptimisticLockingFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TokenRefresher {
    private static final Logger log = LoggerFactory.getLogger(TokenRefresher.class);
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(5);

    private final CalendarConnectionRepository connectionRepository;
    private final TokenCipher tokenCipher;
    private final GoogleApiClient googleApiClient;
    private final Counter tokenRefreshFailureCount;
    private final Counter tokenRefreshSuccessCount;

    public TokenRefresher(CalendarConnectionRepository connectionRepository,
                          TokenCipher tokenCipher,
                          GoogleApiClient googleApiClient,
                          MeterRegistry meterRegistry) {
        this.connectionRepository = connectionRepository;
        this.tokenCipher = tokenCipher;
        this.googleApiClient = googleApiClient;
        this.tokenRefreshFailureCount = meterRegistry.counter("token_refresh_failures_count");
        this.tokenRefreshSuccessCount = meterRegistry.counter("token_refresh_success_count");
    }

    @Transactional
    public <T> T executeWithValidToken(UUID connectionId, Function<String, T> operation) {
        CalendarConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Calendar connection not found"));

        if (connection.getStatus() == CalendarConnectionStatus.REVOKED) {
            throw new IllegalStateException("Calendar connection revoked");
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
                    markRevoked(connection);
                }
                throw second;
            }
        }
    }

    private String ensureActiveAccessToken(CalendarConnection connection) {
        Instant now = Instant.now();
        if (connection.getAccessToken() == null || connection.getExpiresAt().minus(REFRESH_SKEW).isBefore(now)) {
            return refreshConnectionToken(connection);
        }
        return connection.getAccessToken();
    }

    private String refreshConnectionToken(CalendarConnection connection) {
        String refreshToken = tokenCipher.decrypt(connection.getRefreshTokenCiphertext());
        try {
            TokenRefreshResult refresh = googleApiClient.refreshAccessToken(refreshToken);
            connection.setAccessToken(refresh.accessToken());
            connection.setExpiresAt(refresh.expiresAt());
            String token = saveRefreshedTokenWithRetry(connection, refresh.accessToken(), refresh.expiresAt());
            tokenRefreshSuccessCount.increment();
            log.info("{{\"event\":\"token_refresh_success\",\"connectionId\":\"{}\"}}", connection.getId());
            return token;
        } catch (RuntimeException ex) {
            tokenRefreshFailureCount.increment();
            log.warn("{{\"event\":\"token_refresh_failure\",\"connectionId\":\"{}\"}}", connection.getId(), ex);
            markRevoked(connection);
            throw ex;
        }
    }

    private void markRevoked(CalendarConnection connection) {
        connection.setStatus(CalendarConnectionStatus.REVOKED);
        saveStatusWithRetry(connection);
    }

    private String saveRefreshedTokenWithRetry(CalendarConnection connection,
                                               String refreshedAccessToken,
                                               Instant refreshedExpiresAt) {
        try {
            connectionRepository.saveAndFlush(connection);
            return refreshedAccessToken;
        } catch (OptimisticLockingFailureException conflict) {
            CalendarConnection latest = connectionRepository.findById(connection.getId())
                    .orElseThrow(() -> conflict);
            // Prevent token regression: keep the most recent valid expiry.
            if (latest.getExpiresAt() != null && latest.getExpiresAt().isAfter(refreshedExpiresAt)) {
                return latest.getAccessToken();
            }
            latest.setAccessToken(refreshedAccessToken);
            latest.setExpiresAt(refreshedExpiresAt);
            connectionRepository.saveAndFlush(latest);
            return refreshedAccessToken;
        }
    }

    private void saveStatusWithRetry(CalendarConnection connection) {
        try {
            connectionRepository.saveAndFlush(connection);
        } catch (OptimisticLockingFailureException conflict) {
            CalendarConnection latest = connectionRepository.findById(connection.getId())
                    .orElseThrow(() -> conflict);
            latest.setStatus(connection.getStatus());
            connectionRepository.saveAndFlush(latest);
        }
    }
}
