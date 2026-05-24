package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.calendar.auth.MicrosoftAccountClassifier;
import com.daedalussystems.easySchedule.calendar.auth.OAuthStateException;
import com.daedalussystems.easySchedule.calendar.auth.OAuthStatePayload;
import com.daedalussystems.easySchedule.calendar.auth.OAuthStateService;
import com.daedalussystems.easySchedule.calendar.auth.TokenCipher;
import com.daedalussystems.easySchedule.calendar.client.MicrosoftApiClient;
import com.daedalussystems.easySchedule.calendar.client.OAuthTokenExchangeResult;
import com.daedalussystems.easySchedule.calendar.config.MicrosoftOAuthProperties;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MicrosoftCalendarOAuthService {
    private static final CalendarProviderType MICROSOFT_PROVIDER = CalendarProviderType.MICROSOFT;
    private static final Logger log = LoggerFactory.getLogger(MicrosoftCalendarOAuthService.class);

    private final CalendarConnectionRepository repository;
    private final MicrosoftApiClient microsoftApiClient;
    private final MicrosoftOAuthProperties properties;
    private final OAuthStateService stateService;
    private final TokenCipher tokenCipher;
    private final CalendarEventIngestionService ingestionService;
    private final CalendarSyncClientRegistry syncClientRegistry;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarConnectionWriteService connectionWriteService;
    private final String webhookAddress;
    private final String webhookClientState;
    private final long webhookTtlSeconds;

    public MicrosoftCalendarOAuthService(CalendarConnectionRepository repository,
                                         MicrosoftApiClient microsoftApiClient,
                                         MicrosoftOAuthProperties properties,
                                         OAuthStateService stateService,
                                         TokenCipher tokenCipher,
                                         CalendarEventIngestionService ingestionService,
                                         CalendarSyncClientRegistry syncClientRegistry,
                                         SlotCacheVersionService slotCacheVersionService,
                                         CalendarConnectionWriteService connectionWriteService,
                                         @Value("${calendar.webhook.provider.microsoft.address:http://localhost:8080/integrations/calendar/webhooks/microsoft}") String webhookAddress,
                                         @Value("${calendar.webhook.shared-secret:}") String webhookClientState,
                                         @Value("${calendar.webhook.provider.microsoft.ttl-seconds:7200}") long webhookTtlSeconds) {
        this.repository = repository;
        this.microsoftApiClient = microsoftApiClient;
        this.properties = properties;
        this.stateService = stateService;
        this.tokenCipher = tokenCipher;
        this.ingestionService = ingestionService;
        this.syncClientRegistry = syncClientRegistry;
        this.slotCacheVersionService = slotCacheVersionService;
        this.connectionWriteService = connectionWriteService;
        this.webhookAddress = webhookAddress;
        this.webhookClientState = webhookClientState;
        this.webhookTtlSeconds = Math.max(900L, webhookTtlSeconds);
    }

    public String buildMicrosoftConnectUrl(UUID userId, String source, String returnTo, String bookingSessionId) {
        String effectiveSource = (source == null || source.isBlank()) ? OAuthStateService.SOURCE_DASHBOARD : source;
        String state = stateService.generate(userId, effectiveSource, returnTo, bookingSessionId);
        String scope = String.join(" ", properties.getScopes());
        return "https://login.microsoftonline.com/" + enc(properties.getTenantId()) + "/oauth2/v2.0/authorize"
                + "?client_id=" + enc(properties.getClientId())
                + "&redirect_uri=" + enc(properties.getRedirectUri())
                + "&response_type=code"
                + "&response_mode=query"
                + "&scope=" + enc(scope)
                + "&state=" + enc(state);
    }

    @Transactional
    public CalendarOAuthService.OAuthCallbackResult handleMicrosoftCallback(String code, String state) {
        OAuthStatePayload payload = stateService.validateAndExtract(state);
        UUID userId = payload.userId();
        if (userId == null) {
            throw new OAuthStateException(OAuthStateException.Reason.MISSING_USER, "OAuth state missing userId");
        }
        Optional<CalendarConnection> existing = repository.findByUserIdAndProvider(userId, MICROSOFT_PROVIDER);
        OAuthTokenExchangeResult token = microsoftApiClient.exchangeCodeForToken(
                code,
                properties.getRedirectUri(),
                properties.getClientId(),
                properties.getClientSecret(),
                properties.getTenantId());

        if (token.accessToken() == null || token.expiresAt() == null) {
            throw new IllegalArgumentException("Invalid token response from provider");
        }
        MicrosoftApiClient.ProviderUserProfile profile = microsoftApiClient.fetchProviderUserProfile(token.accessToken());
        String providerUserId = profile == null ? null : profile.id();
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("Provider user id missing");
        }
        // Classify on the OAuth-callback path so the very first booking after a fresh
        // connect already sees the right invite_delivery capability. The post-CREATE
        // path remains as a backstop to correct any mailbox we couldn't classify here.
        String identityForClassification = firstNonBlank(profile.userPrincipalName(), profile.mail());
        MicrosoftAccountClassifier.Classification classification =
                MicrosoftAccountClassifier.classifyByEmail(identityForClassification);

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
        connection.setProvider(MICROSOFT_PROVIDER);
        connection.setProviderUserId(providerUserId);
        connection.setRefreshTokenCiphertext(refreshTokenCiphertext);
        connection.setLastTokenExpiresAt(token.expiresAt().isBefore(Instant.now()) ? Instant.now().plusSeconds(300) : token.expiresAt());
        connection.setScopes(properties.getScopes());
        connection.setStatus(CalendarConnectionStatus.SYNCING);
        connection.setLastErrorCode(null);
        connection.setLastErrorAt(null);
        // Stamp capability in the same snapshot — atomic with the connection's initial
        // persistence, so no booking can ever see a row with classification=null.
        connection.setAccountClassification(classification.accountClassification());
        connection.setOrganizerInviteDelivery(classification.organizerInviteDelivery());
        log.info("microsoft_oauth_account_capability_classified userId={} classification={} inviteDelivery={} identitySource={}",
                userId, classification.accountClassification(), classification.organizerInviteDelivery(),
                profile.userPrincipalName() != null ? "userPrincipalName" : (profile.mail() != null ? "mail" : "[absent]"));
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
            saved.setStatus(CalendarConnectionStatus.ACTIVE);
            slotCacheVersionService.bumpVersion(userId);
        } catch (RuntimeException ex) {
            saved.setStatus(CalendarConnectionStatus.FAILED);
            saved.setLastErrorCode("INITIAL_SYNC_FAILED");
            saved.setLastErrorAt(Instant.now());
        }
        if (saved.getStatus() == CalendarConnectionStatus.ACTIVE) {
            createWebhookSubscription(saved.getId(), token.accessToken());
        }
        connectionWriteService.saveSnapshot(saved, "oauth_callback_final");
        return new CalendarOAuthService.OAuthCallbackResult(payload.source(), payload.returnTo(), payload.bookingSessionId());
    }

    @Transactional(readOnly = true)
    public String microsoftConnectionStatus(UUID userId) {
        Optional<CalendarConnection> connection = repository.findByUserIdAndProvider(userId, MICROSOFT_PROVIDER);
        if (connection.isEmpty()) {
            return "NOT_CONNECTED";
        }
        return mapPublicConnectionStatus(connection.get().getStatus());
    }

    @Transactional
    public void disconnectMicrosoft(UUID userId) {
        Optional<CalendarConnection> existing = repository.findByUserIdAndProvider(userId, MICROSOFT_PROVIDER);
        if (existing.isPresent()) {
            CalendarConnection connection = existing.get();
            connectionWriteService.markFailure(connection.getId(),
                    CalendarConnectionStatus.REVOKED,
                    connection.getLastErrorCode(),
                    connection.getLastErrorAt(),
                    "oauth_disconnect");
        }
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findMicrosoftConnectionIdByWebhookChannel(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByWebhookChannelId(subscriptionId)
                .filter(connection -> connection.getProvider() == MICROSOFT_PROVIDER)
                .map(CalendarConnection::getId);
    }

    private void createWebhookSubscription(UUID connectionId, String accessToken) {
        if (accessToken == null || accessToken.isBlank()
                || webhookAddress == null || webhookAddress.isBlank()
                || webhookClientState == null || webhookClientState.isBlank()) {
            return;
        }
        try {
            Instant requestedExpiry = Instant.now().plusSeconds(webhookTtlSeconds);
            MicrosoftApiClient.WebhookSubscription subscription = microsoftApiClient.createEventSubscription(
                    accessToken,
                    webhookAddress,
                    webhookClientState,
                    requestedExpiry);
            if (subscription.subscriptionId() != null && !subscription.subscriptionId().isBlank()) {
                connectionWriteService.updateWebhookChannel(
                        connectionId,
                        subscription.subscriptionId(),
                        subscription.resourceId(),
                        subscription.expiresAt(),
                        "microsoft_webhook_subscribe");
            }
        } catch (RuntimeException ex) {
            log.warn("microsoft_webhook_subscribe_failed connectionId={}", connectionId, ex);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
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
        copy.setWebhookChannelId(existing.getWebhookChannelId());
        copy.setWebhookResourceId(existing.getWebhookResourceId());
        copy.setWebhookChannelExpiresAt(existing.getWebhookChannelExpiresAt());
        copy.setAccountClassification(existing.getAccountClassification());
        copy.setOrganizerInviteDelivery(existing.getOrganizerInviteDelivery());
        return copy;
    }
}
