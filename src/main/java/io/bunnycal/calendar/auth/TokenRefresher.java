package io.bunnycal.calendar.auth;

import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.OAuthError;
import io.bunnycal.calendar.client.OAuthErrorCategory;
import io.bunnycal.calendar.client.TokenRefreshResult;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.service.CalendarConnectionWriteService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
    private final CalendarTokenClientRegistry tokenClientRegistry;
    private final CalendarConnectionWriteService connectionWriteService;
    private final AccessTokenCache accessTokenCache;
    private final Counter tokenRefreshFailureCount;
    private final Counter tokenRefreshSuccessCount;
    private final MeterRegistry meterRegistry;

    public TokenRefresher(CalendarConnectionRepository connectionRepository,
                          TokenCipher tokenCipher,
                          CalendarTokenClientRegistry tokenClientRegistry,
                          CalendarConnectionWriteService connectionWriteService,
                          AccessTokenCache accessTokenCache,
                          MeterRegistry meterRegistry) {
        this.connectionRepository = connectionRepository;
        this.tokenCipher = tokenCipher;
        this.tokenClientRegistry = tokenClientRegistry;
        this.connectionWriteService = connectionWriteService;
        this.accessTokenCache = accessTokenCache;
        this.meterRegistry = meterRegistry;
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
                    // Second 401 after a fresh access token → grant is dead.
                    OAuthError err = second.getOAuthError();
                    markFailed(connection,
                            err != null ? err.stableCode() : "unauthorized",
                            OAuthErrorCategory.TERMINAL);
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
        CalendarProviderType provider = connection.getProvider();
        if (provider == null) {
            throw new IllegalStateException(
                    "Calendar connection " + connection.getId() + " is missing provider; cannot refresh token");
        }
        CalendarTokenClient tokenClient = tokenClientRegistry.clientFor(provider);
        String currentRefreshToken = tokenCipher.decrypt(connection.getRefreshTokenCiphertext());
        try {
            TokenRefreshResult refresh = tokenClient.refreshAccessToken(currentRefreshToken);

            String rotatedCiphertext = null;
            String rotatedPlain = refresh.refreshToken();
            if (rotatedPlain != null && !rotatedPlain.isBlank()
                    && !rotatedPlain.equals(currentRefreshToken)) {
                rotatedCiphertext = tokenCipher.encrypt(rotatedPlain);
                if (meterRegistry != null) {
                    meterRegistry.counter("calendar.refresh_token.rotated.total",
                                    "provider", provider.name().toLowerCase(Locale.ROOT))
                            .increment();
                }
                log.debug("calendar_refresh_token_rotated provider={} connectionId={} previousTokenHashPrefix={} newTokenHashPrefix={}",
                        provider, connection.getId(),
                        tokenFingerprint(currentRefreshToken), tokenFingerprint(rotatedPlain));
            }

            String token = saveRefreshedToken(connection.getId(),
                    refresh.accessToken(),
                    refresh.expiresAt(),
                    connection.getLastSyncedAt(),
                    rotatedCiphertext);
            accessTokenCache.put(connection.getId(), token, refresh.expiresAt());
            tokenRefreshSuccessCount.increment();
            log.debug("{{\"event\":\"token_refresh_success\",\"connectionId\":\"{}\",\"provider\":\"{}\"}}",
                    connection.getId(), provider);
            return token;
        } catch (RuntimeException ex) {
            tokenRefreshFailureCount.increment();
            OAuthErrorCategory category = categoryOf(ex);
            String errorCode = errorCodeOf(ex);
            if (provider == CalendarProviderType.MICROSOFT) {
                meterRegistry.counter("microsoft_token_refresh_failures_total",
                        "provider", "microsoft",
                        "connectionId", connection.getId().toString(),
                        "calendarId", "primary",
                        "tenantId", "unknown",
                        "ingestionMode", "token_refresh",
                        "syncType", "auth").increment();
            }
            log.warn("{{\"event\":\"token_refresh_failure\",\"connectionId\":\"{}\",\"provider\":\"{}\",\"category\":\"{}\",\"errorCode\":\"{}\"}}",
                    connection.getId(), provider, category, errorCode, ex);
            markFailed(connection, errorCode, category);
            throw ex;
        }
    }

    private void markFailed(CalendarConnection connection, String errorCode, OAuthErrorCategory category) {
        accessTokenCache.remove(connection.getId());
        connectionWriteService.markFailureWithCategory(
                connection.getId(),
                category,
                errorCode,
                Instant.now(),
                "token_refresh_mark_failed");
    }

    private String saveRefreshedToken(UUID connectionId,
                                      String refreshedAccessToken,
                                      Instant refreshedExpiresAt,
                                      Instant lastSyncedAt,
                                      String rotatedRefreshTokenCiphertext) {
        if (rotatedRefreshTokenCiphertext != null) {
            connectionWriteService.markActiveWithRotatedToken(
                    connectionId,
                    refreshedExpiresAt,
                    lastSyncedAt,
                    rotatedRefreshTokenCiphertext,
                    "token_refresh_success_rotated");
        } else {
            connectionWriteService.markActive(
                    connectionId,
                    refreshedExpiresAt,
                    lastSyncedAt,
                    "token_refresh_success");
        }
        return refreshedAccessToken;
    }

    /**
     * F6: prefer the typed {@link OAuthError} carried on {@link CalendarClientException} when
     * present; fall back to substring matching for transient errors and unknown
     * RuntimeExceptions raised pre-classify.
     */
    private static OAuthErrorCategory categoryOf(RuntimeException ex) {
        if (ex instanceof CalendarClientException cce) {
            OAuthError err = cce.getOAuthError();
            if (err != null) {
                return err.category();
            }
            // CalendarClientException without OAuthError — derive from status code.
            int status = cce.getStatusCode();
            if (status == 401 || status == 403) {
                return OAuthErrorCategory.TERMINAL;
            }
            if (status == 429 || status >= 500) {
                return OAuthErrorCategory.TRANSIENT;
            }
            return OAuthErrorCategory.UNKNOWN;
        }
        // Legacy path: untyped RuntimeException from a token client.
        String message = ex.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("invalid_grant")
                    || normalized.contains("invalid_token")
                    || normalized.contains("access_denied")
                    || normalized.contains("unauthorized_client")) {
                return OAuthErrorCategory.TERMINAL;
            }
        }
        // Network/timeout/unclassified — treat as transient so we back off and retry, not REVOKE.
        return OAuthErrorCategory.TRANSIENT;
    }

    private static String errorCodeOf(RuntimeException ex) {
        if (ex instanceof CalendarClientException cce && cce.getOAuthError() != null) {
            return cce.getOAuthError().stableCode();
        }
        String message = ex.getMessage();
        if (message != null && !message.isBlank()) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("invalid_grant")) return "invalid_grant";
            if (normalized.contains("invalid_token")) return "invalid_token";
            if (normalized.contains("access_denied")) return "access_denied";
            if (normalized.contains("unauthorized_client")) return "unauthorized_client";
        }
        return ex.getClass().getSimpleName();
    }

    /**
     * Short non-reversible fingerprint of a refresh token. Used in logs to distinguish "the
     * token changed" without ever exposing the secret. Always 8 chars of base64url-encoded
     * SHA-256.
     */
    private static String tokenFingerprint(String token) {
        if (token == null || token.isBlank()) {
            return "<none>";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return encoded.substring(0, Math.min(8, encoded.length()));
        } catch (Exception ex) {
            return "<hash-err>";
        }
    }
}
