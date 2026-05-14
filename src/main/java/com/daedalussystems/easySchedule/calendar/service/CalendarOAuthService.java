package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.calendar.auth.OAuthStateService;
import com.daedalussystems.easySchedule.calendar.auth.OAuthStatePayload;
import com.daedalussystems.easySchedule.calendar.auth.OAuthStateException;
import com.daedalussystems.easySchedule.calendar.auth.TokenCipher;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import com.daedalussystems.easySchedule.calendar.client.OAuthTokenExchangeResult;
import com.daedalussystems.easySchedule.calendar.config.GoogleOAuthProperties;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarOAuthService {
    private static final Logger log = LoggerFactory.getLogger(CalendarOAuthService.class);
    private static final CalendarProviderType GOOGLE_PROVIDER = CalendarProviderType.GOOGLE;
    private final CalendarConnectionRepository repository;
    private final GoogleApiClient googleApiClient;
    private final GoogleOAuthProperties properties;
    private final OAuthStateService stateService;
    private final TokenCipher tokenCipher;
    private final CalendarEventIngestionService ingestionService;
    private final ExternalCalendarSyncClient syncClient;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarConnectionWriteService connectionWriteService;

    public CalendarOAuthService(CalendarConnectionRepository repository,
                                GoogleApiClient googleApiClient,
                                GoogleOAuthProperties properties,
                                OAuthStateService stateService,
                                TokenCipher tokenCipher,
                                CalendarEventIngestionService ingestionService,
                                ExternalCalendarSyncClient syncClient,
                                SlotCacheVersionService slotCacheVersionService,
                                CalendarConnectionWriteService connectionWriteService) {
        this.repository = repository;
        this.googleApiClient = googleApiClient;
        this.properties = properties;
        this.stateService = stateService;
        this.tokenCipher = tokenCipher;
        this.ingestionService = ingestionService;
        this.syncClient = syncClient;
        this.slotCacheVersionService = slotCacheVersionService;
        this.connectionWriteService = connectionWriteService;
    }

    public String buildGoogleConnectUrl(UUID userId) {
        return buildGoogleConnectUrl(userId, OAuthStateService.SOURCE_DASHBOARD, null, null);
    }

    public String buildGoogleConnectUrl(UUID userId, String source, String returnTo, String bookingSessionId) {
        String effectiveSource = (source == null || source.isBlank()) ? OAuthStateService.SOURCE_DASHBOARD : source;
        String state = stateService.generate(userId, effectiveSource, returnTo, bookingSessionId);
        String clientId = properties.getClientId();
        log.info("DEBUG Google clientId='{}'", clientId);
        Assert.hasText(clientId, "Google clientId must not be empty");
        String scope = String.join(" ", properties.getScopes());
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(properties.getRedirectUri())
                + "&response_type=code"
                + "&access_type=offline"
                + "&prompt=consent"
                + "&scope=" + enc(scope)
                + "&state=" + enc(state);
    }

    @Transactional
    public OAuthCallbackResult handleGoogleCallback(String code, String state) {
        OAuthStatePayload payload = stateService.validateAndExtract(state);
        UUID userId = payload.userId();
        if (userId == null) {
            throw new OAuthStateException(OAuthStateException.Reason.MISSING_USER, "OAuth state missing userId");
        }
        String stateRef = Integer.toHexString(state == null ? 0 : state.hashCode());
        log.info("oauth_state_resolution stateRef={} resolvedUserId={} source={} returnTo={} bookingSessionIdPresent={}",
                stateRef, userId, payload.source(), payload.returnTo(), payload.bookingSessionId() != null);
        log.info("calendar_connection_lookup_by_user provider={} userId={}", GOOGLE_PROVIDER, userId);
        Optional<CalendarConnection> existing = repository.findByUserIdAndProvider(userId, GOOGLE_PROVIDER);
        OAuthTokenExchangeResult token = googleApiClient.exchangeCodeForToken(
                code,
                properties.getRedirectUri(),
                properties.getClientId(),
                properties.getClientSecret());

        if (token.accessToken() == null || token.expiresAt() == null) {
            throw new IllegalArgumentException("Invalid token response from provider");
        }
        String providerUserId = googleApiClient.fetchProviderUserId(token.accessToken());
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("Provider user id missing");
        }

        CalendarConnection base = existing.orElse(null);
        CalendarConnection connection = base == null ? new CalendarConnection() : copyForUpdate(base);
        String refreshTokenCiphertext;
        if (token.refreshToken() != null && !token.refreshToken().isBlank()) {
            refreshTokenCiphertext = tokenCipher.encrypt(token.refreshToken());
        } else if (base != null && base.getRefreshTokenCiphertext() != null && !base.getRefreshTokenCiphertext().isBlank()) {
            refreshTokenCiphertext = base.getRefreshTokenCiphertext();
        } else {
            throw new IllegalArgumentException("Refresh token missing from provider response");
        }

        connection.setUserId(userId);
        connection.setProvider(GOOGLE_PROVIDER);
        connection.setProviderUserId(providerUserId);
        connection.setRefreshTokenCiphertext(refreshTokenCiphertext);
        connection.setLastTokenExpiresAt(token.expiresAt().isBefore(Instant.now()) ? Instant.now().plusSeconds(300) : token.expiresAt());
        connection.setScopes(properties.getScopes());
        connection.setStatus(CalendarConnectionStatus.SYNCING);
        connection.setLastErrorCode(null);
        connection.setLastErrorAt(null);
        CalendarConnection saved = connectionWriteService.saveSnapshot(connection, "oauth_callback_initial");

        try {
            ExternalCalendarSyncClient.SyncBatch fullBatch =
                    syncClient.fetchFull(saved, SyncSourceAttribution.USER_ACTION);
            ingestionService.upsertEvents(saved.getId(), fullBatch.events(), SyncSourceAttribution.USER_ACTION);
            if (fullBatch.nextCursor() != null) {
                connectionWriteService.advanceProviderCursor(
                        saved.getId(), null, fullBatch.nextCursor(), Instant.now(), "oauth_initial_full_cursor_advance");
            }
            saved.setStatus(CalendarConnectionStatus.ACTIVE);
            slotCacheVersionService.bumpVersion(userId);
        } catch (RuntimeException ex) {
            saved.setStatus(CalendarConnectionStatus.FAILED);
            saved.setLastErrorCode("INITIAL_SYNC_FAILED");
            saved.setLastErrorAt(Instant.now());
            log.warn("initial calendar sync failed for userId={} connectionId={}", userId, saved.getId(), ex);
        }
        connectionWriteService.saveSnapshot(saved, "oauth_callback_final");
        log.info("{{\"event\":\"calendar_connected\",\"userId\":\"{}\",\"provider\":\"GOOGLE\"}}", userId);
        return new OAuthCallbackResult(payload.source(), payload.returnTo(), payload.bookingSessionId());
    }

    @Transactional(readOnly = true)
    public String googleConnectionStatus(UUID userId) {
        Optional<CalendarConnection> connection = repository.findByUserIdAndProvider(userId, GOOGLE_PROVIDER);
        if (connection.isEmpty()) {
            return "NOT_CONNECTED";
        }
        return mapPublicConnectionStatus(connection.get().getStatus());
    }

    @Transactional
    public void disconnectGoogle(UUID userId) {
        Optional<CalendarConnection> existing = repository.findByUserIdAndProvider(userId, GOOGLE_PROVIDER);
        if (existing.isPresent()) {
            CalendarConnection connection = existing.get();
            connectionWriteService.markFailure(connection.getId(),
                    CalendarConnectionStatus.REVOKED,
                    connection.getLastErrorCode(),
                    connection.getLastErrorAt(),
                    "oauth_disconnect");
            log.info("{{\"event\":\"calendar_disconnected\",\"userId\":\"{}\",\"provider\":\"GOOGLE\"}}", userId);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String mapPublicConnectionStatus(CalendarConnectionStatus status) {
        if (status == CalendarConnectionStatus.ACTIVE) {
            return "CONNECTED";
        }
        if (status == CalendarConnectionStatus.REVOKED || status == CalendarConnectionStatus.DISCONNECTED) {
            return "DISCONNECTED";
        }
        return "ERROR";
    }

    private static CalendarConnection copyForUpdate(CalendarConnection existing) {
        CalendarConnection copy = new CalendarConnection();
        try {
            java.lang.reflect.Field idField = CalendarConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(copy, existing.getId());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to copy calendar connection id", ex);
        }
        copy.setUserId(existing.getUserId());
        copy.setProvider(existing.getProvider());
        copy.setProviderUserId(existing.getProviderUserId());
        copy.setRefreshTokenCiphertext(existing.getRefreshTokenCiphertext());
        copy.setLastTokenExpiresAt(existing.getLastTokenExpiresAt());
        copy.setScopes(existing.getScopes());
        copy.setStatus(existing.getStatus());
        copy.setLastErrorCode(existing.getLastErrorCode());
        copy.setLastErrorAt(existing.getLastErrorAt());
        copy.setLastSyncedAt(existing.getLastSyncedAt());
        copy.setProviderSyncCursor(existing.getProviderSyncCursor());
        copy.setProviderCursorUpdatedAt(existing.getProviderCursorUpdatedAt());
        copy.setProviderCursorInvalidatedAt(existing.getProviderCursorInvalidatedAt());
        return copy;
    }

    public record OAuthCallbackResult(String source, String returnTo, String bookingSessionId) {}
}
