package io.bunnycal.calendar.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.availability.service.ParticipantEligibilityService;
import io.bunnycal.team.service.ParticipantSetupRequestService;
import io.bunnycal.calendar.auth.OAuthStateService;
import io.bunnycal.calendar.auth.OAuthStatePayload;
import io.bunnycal.calendar.auth.OAuthStateException;
import io.bunnycal.calendar.auth.TokenCipher;
import io.bunnycal.calendar.client.GoogleApiClient;
import io.bunnycal.calendar.client.OAuthTokenExchangeResult;
import io.bunnycal.calendar.client.TokenRefreshResult;
import io.bunnycal.calendar.config.CalendarWebhookProperties;
import io.bunnycal.calendar.config.GoogleOAuthProperties;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.logging.OpsLoggers;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final CalendarSyncClientRegistry syncClientRegistry;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarConnectionWriteService connectionWriteService;
    private final MeterRegistry meterRegistry;
    private final CalendarWebhookProperties webhookProperties;
    private final CalendarInventoryHydrator inventoryHydrator;
    private final ParticipantSetupRequestService setupRequestService;
    private final ParticipantEligibilityService eligibilityService;
    private final CalendarConnectionLimitGuard connectionLimitGuard;

    public CalendarOAuthService(CalendarConnectionRepository repository,
                                GoogleApiClient googleApiClient,
                                GoogleOAuthProperties properties,
                                OAuthStateService stateService,
                                TokenCipher tokenCipher,
                                CalendarEventIngestionService ingestionService,
                                CalendarSyncClientRegistry syncClientRegistry,
                                SlotCacheVersionService slotCacheVersionService,
                                CalendarConnectionWriteService connectionWriteService,
                                MeterRegistry meterRegistry,
                                CalendarWebhookProperties webhookProperties,
                                CalendarInventoryHydrator inventoryHydrator,
                                ParticipantSetupRequestService setupRequestService,
                                ParticipantEligibilityService eligibilityService,
                                CalendarConnectionLimitGuard connectionLimitGuard) {
        this.repository = repository;
        this.googleApiClient = googleApiClient;
        this.properties = properties;
        this.stateService = stateService;
        this.tokenCipher = tokenCipher;
        this.ingestionService = ingestionService;
        this.syncClientRegistry = syncClientRegistry;
        this.slotCacheVersionService = slotCacheVersionService;
        this.connectionWriteService = connectionWriteService;
        this.meterRegistry = meterRegistry;
        this.webhookProperties = webhookProperties;
        this.inventoryHydrator = inventoryHydrator;
        this.setupRequestService = setupRequestService;
        this.eligibilityService = eligibilityService;
        this.connectionLimitGuard = connectionLimitGuard;
    }

    public String buildGoogleConnectUrl(UUID userId) {
        return buildGoogleConnectUrl(userId, OAuthStateService.SOURCE_DASHBOARD, null, null);
    }

    public String buildGoogleConnectUrl(UUID userId, String source, String returnTo, String bookingSessionId) {
        String effectiveSource = (source == null || source.isBlank()) ? OAuthStateService.SOURCE_DASHBOARD : source;
        String state = stateService.generate(userId, effectiveSource, returnTo, bookingSessionId);
        String clientId = properties.getClientId();
        log.debug("google_oauth_client_id_present clientIdPresent={}", clientId != null && !clientId.isBlank());
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

        // The row is keyed by the external ACCOUNT, not just the provider — a user may connect
        // several Google accounts. Which account this is only becomes known after the token
        // exchange, so both the lookup and the plan-limit decision have to follow it. Empty means
        // a net-new account (INSERT); present means re-auth or reconnect of one we already hold
        // (UPDATE), including a REVOKED row, which the finder deliberately still returns.
        Optional<CalendarConnection> existing =
                repository.findByUserIdAndProviderAndProviderUserId(userId, GOOGLE_PROVIDER, providerUserId);
        connectionLimitGuard.requireCanConnect(userId, existing.isEmpty());

        CalendarConnection base = existing.orElse(null);
        CalendarConnection connection = base == null ? new CalendarConnection() : copyForUpdate(base);
        String refreshTokenCiphertext;
        if (token.refreshToken() != null && !token.refreshToken().isBlank()) {
            refreshTokenCiphertext = tokenCipher.encrypt(token.refreshToken());
        } else if (base != null && base.getRefreshTokenCiphertext() != null && !base.getRefreshTokenCiphertext().isBlank()) {
            // Google omits refresh_token on re-consent. Carrying the stored one forward is only
            // safe because `base` is now guaranteed to be the same external account.
            refreshTokenCiphertext = base.getRefreshTokenCiphertext();
        } else {
            throw new IllegalArgumentException("Refresh token missing from provider response");
        }

        connection.setUserId(userId);
        connection.setProvider(GOOGLE_PROVIDER);
        connection.setProviderUserId(providerUserId);
        String accountEmail = fetchGoogleAccountEmail(token.accessToken());
        if (accountEmail != null && !accountEmail.isBlank()) {
            connection.setAccountEmail(accountEmail);
        }
        connection.setRefreshTokenCiphertext(refreshTokenCiphertext);
        connection.setLastTokenExpiresAt(token.expiresAt().isBefore(Instant.now()) ? Instant.now().plusSeconds(300) : token.expiresAt());
        connection.setScopes(properties.getScopes());
        // Reactivates a REVOKED row on reconnect, as well as clearing any prior error.
        connection.setStatus(CalendarConnectionStatus.SYNCING);
        connection.setLastErrorCode(null);
        connection.setLastErrorAt(null);
        // First connection for this user becomes the round-robin write-back target; later ones
        // do not steal it. Users can re-point it from the Integrations page.
        if (base == null && repository.findByUserIdAndDefaultWritebackTrue(userId).isEmpty()) {
            connection.setDefaultWriteback(true);
        }
        CalendarConnection saved = connectionWriteService.saveSnapshot(connection, "oauth_callback_initial");

        try {
            ExternalCalendarSyncClient syncClient = syncClientRegistry.clientFor(saved);
            ExternalCalendarSyncClient.SyncBatch fullBatch =
                    syncClient.fetchFull(saved, SyncSourceAttribution.USER_ACTION);
            ingestionService.upsertEvents(saved.getId(), fullBatch.events(), SyncSourceAttribution.USER_ACTION);
            if (fullBatch.nextCursor() != null) {
                connectionWriteService.advanceProviderCursor(
                        saved.getId(), null, fullBatch.nextCursor(), Instant.now(), "oauth_initial_full_cursor_advance");
            }
            String googleWebhookAddress = webhookProperties.getProvider().getGoogle().getAddress();
            String googleWebhookToken = webhookProperties.getSharedSecret();
            if (webhookProperties.isProviderWebhookEnabled(GOOGLE_PROVIDER)
                    && googleWebhookAddress != null && !googleWebhookAddress.isBlank()
                    && googleWebhookToken != null && !googleWebhookToken.isBlank()) {
                try {
                    GoogleApiClient.WatchChannel watch = googleApiClient.watchEvents(
                            token.accessToken(),
                            googleWebhookAddress,
                            googleWebhookToken);
                    saved.setWebhookChannelId(watch.channelId());
                    saved.setWebhookResourceId(watch.resourceId());
                    saved.setWebhookChannelExpiresAt(watch.expiration());
                } catch (RuntimeException ex) {
                    log.warn("google_watch_registration_failed userId={} connectionId={}", userId, saved.getId(), ex);
                }
            }
            saved.setStatus(CalendarConnectionStatus.ACTIVE);
            slotCacheVersionService.bumpVersionAfterCommit(userId);
        } catch (RuntimeException ex) {
            saved.setStatus(CalendarConnectionStatus.FAILED);
            saved.setLastErrorCode("INITIAL_SYNC_FAILED");
            saved.setLastErrorAt(Instant.now());
            log.warn("initial calendar sync failed for userId={} connectionId={}", userId, saved.getId(), ex);
        }
        connectionWriteService.saveSnapshot(saved, "oauth_callback_final");
        // Best-effort: hydrate the canonical provider calendar inventory using the freshly-issued
        // access token. Failures are swallowed inside the hydrator so they cannot fail the connect.
        inventoryHydrator.hydrateWithAccessToken(saved, token.accessToken());
        if (saved.getStatus() == CalendarConnectionStatus.ACTIVE && eligibilityService.isReady(userId)) {
            setupRequestService.markAllCompletedForTarget(userId);
        }
        log.info("{{\"event\":\"calendar_connected\",\"userId\":\"{}\",\"provider\":\"GOOGLE\"}}", userId);
        OpsLoggers.HOST.info(
                "calendar_connection_created hostId={} connectionId={} provider={} status={} scopes={} reauth={}",
                userId, saved.getId(), GOOGLE_PROVIDER, saved.getStatus(),
                saved.getScopes() == null ? 0 : saved.getScopes().size(), existing.isPresent());
        return new OAuthCallbackResult(payload.source(), payload.returnTo(), payload.bookingSessionId());
    }

    /**
     * The user's aggregate Google status. With several accounts connected, the provider as a whole
     * is CONNECTED if any one of them is live; only if none are do we report the best of what is
     * left (an error state beats a clean disconnect, so the user is told there is something to fix).
     */
    @Transactional(readOnly = true)
    public String googleConnectionStatus(UUID userId) {
        return aggregateConnectionStatus(repository.findByUserIdAndProviderOrderByCreatedAtAsc(userId, GOOGLE_PROVIDER));
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findGoogleConnectionIdByWebhookChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByWebhookChannelId(channelId)
                .filter(conn -> conn.getProvider() == CalendarProviderType.GOOGLE)
                .map(CalendarConnection::getId);
    }

    /**
     * Disconnects every Google account the user has connected. Kept for the provider-scoped
     * {@code DELETE /integrations/calendar/google} route; the per-account route calls
     * {@link #disconnectConnection(CalendarConnection)} directly.
     */
    @Transactional
    public void disconnectGoogle(UUID userId) {
        for (CalendarConnection connection
                : repository.findByUserIdAndProviderOrderByCreatedAtAsc(userId, GOOGLE_PROVIDER)) {
            disconnectConnection(connection);
        }
    }

    /**
     * Revokes one Google connection. Callers are responsible for the ownership check and for
     * promoting a new default write-back target if this row held the flag.
     */
    @Transactional
    public void disconnectConnection(CalendarConnection connection) {
        UUID userId = connection.getUserId();
        boolean hasCiphertext = connection.getRefreshTokenCiphertext() != null
                && !connection.getRefreshTokenCiphertext().isBlank();

        // F9 ordering: stop the watch channel BEFORE revoking the refresh token. Once
        // revoked, we can no longer obtain an access token to call /channels/stop.
        if (hasCiphertext
                && connection.getWebhookChannelId() != null && !connection.getWebhookChannelId().isBlank()
                && connection.getWebhookResourceId() != null && !connection.getWebhookResourceId().isBlank()) {
            try {
                String refreshToken = tokenCipher.decrypt(connection.getRefreshTokenCiphertext());
                TokenRefreshResult refreshed = googleApiClient.refreshAccessToken(refreshToken);
                googleApiClient.stopWatchChannel(
                        refreshed.accessToken(),
                        connection.getWebhookChannelId(),
                        connection.getWebhookResourceId());
            } catch (RuntimeException ex) {
                log.warn("google_watch_stop_on_disconnect_failed connectionId={} userId={}", connection.getId(), userId, ex);
            }
        }

        if (hasCiphertext) {
            try {
                String refreshToken = tokenCipher.decrypt(connection.getRefreshTokenCiphertext());
                googleApiClient.revokeToken(refreshToken);
            } catch (RuntimeException ex) {
                meterRegistry.counter("oauth_revoke_failure_total", "provider", "google", "errorCode", "revoke_failed")
                        .increment();
                log.warn("google_oauth_revoke_failed connectionId={} userId={}", connection.getId(), userId, ex);
            }
        }

        connectionWriteService.markFailure(connection.getId(),
                CalendarConnectionStatus.REVOKED,
                connection.getLastErrorCode(),
                connection.getLastErrorAt(),
                "oauth_disconnect");
        // F9: clear the encrypted refresh token (and webhook handles) from disk so no
        // revoked secret remains. Order matters — do this AFTER markFailure transitions
        // the row to REVOKED so any racing scheduler tick is already filtered out.
        connectionWriteService.clearRefreshTokenCiphertext(connection.getId(), "oauth_disconnect_clear_token");
        log.info("{{\"event\":\"calendar_disconnected\",\"userId\":\"{}\",\"provider\":\"GOOGLE\"}}", userId);
        OpsLoggers.HOST.info(
                "calendar_connection_disconnected hostId={} connectionId={} provider={} reason={}",
                userId, connection.getId(), GOOGLE_PROVIDER, "USER_INITIATED");
    }

    /**
     * The connected Google account's email address, for display in the Integrations page.
     *
     * <p>Read off the primary entry of the calendar list rather than from an identity endpoint:
     * for Google, the primary calendar's id <em>is</em> the account email, and {@code
     * calendar.readonly} (already granted) covers it. Adding a {@code userinfo.email} scope would
     * force every existing user through re-consent for information we can already see.
     *
     * <p>Best-effort — the UI falls back to {@code providerUserId}, so a failure here must not
     * fail the connect.
     */
    private String fetchGoogleAccountEmail(String accessToken) {
        try {
            return googleApiClient.listCalendars(accessToken).stream()
                    .filter(entry -> entry != null && entry.primary())
                    .map(entry -> entry.externalCalendarId())
                    .filter(id -> id != null && id.contains("@"))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException ex) {
            log.warn("google_account_email_lookup_failed", ex);
            return null;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Collapses a user's connections for one provider into the single provider-level status the
     * Integrations page shows. CONNECTED wins over ERROR wins over DISCONNECTED: one healthy
     * account means the provider works, and a broken account is worth surfacing over a
     * deliberately-removed one.
     */
    static String aggregateConnectionStatus(java.util.List<CalendarConnection> connections) {
        if (connections == null || connections.isEmpty()) {
            return "NOT_CONNECTED";
        }
        boolean anyDisconnected = false;
        boolean anyError = false;
        for (CalendarConnection connection : connections) {
            String status = mapPublicConnectionStatus(connection.getStatus());
            if ("CONNECTED".equals(status)) {
                return "CONNECTED";
            }
            if ("ERROR".equals(status)) {
                anyError = true;
            } else {
                anyDisconnected = true;
            }
        }
        if (anyError) {
            return "ERROR";
        }
        return anyDisconnected ? "DISCONNECTED" : "NOT_CONNECTED";
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
        copy.setAccountEmail(existing.getAccountEmail());
        copy.setDefaultWriteback(existing.isDefaultWriteback());
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
        copy.setWebhookChannelId(existing.getWebhookChannelId());
        copy.setWebhookResourceId(existing.getWebhookResourceId());
        copy.setWebhookChannelExpiresAt(existing.getWebhookChannelExpiresAt());
        return copy;
    }

    public record OAuthCallbackResult(String source, String returnTo, String bookingSessionId) {}
}
