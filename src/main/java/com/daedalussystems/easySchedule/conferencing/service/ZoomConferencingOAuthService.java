package com.daedalussystems.easySchedule.conferencing.service;

import com.daedalussystems.easySchedule.calendar.auth.OAuthStateException;
import com.daedalussystems.easySchedule.calendar.auth.OAuthStatePayload;
import com.daedalussystems.easySchedule.calendar.auth.OAuthStateService;
import com.daedalussystems.easySchedule.calendar.auth.TokenCipher;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import com.daedalussystems.easySchedule.conferencing.client.ZoomApiClient;
import com.daedalussystems.easySchedule.conferencing.domain.ConferencingConnectionStatus;
import com.daedalussystems.easySchedule.conferencing.domain.ZoomConferencingConnection;
import com.daedalussystems.easySchedule.conferencing.repository.ZoomConferencingConnectionRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class ZoomConferencingOAuthService implements ConferencingOAuthService {
    private final ZoomConferencingConnectionRepository repository;
    private final ZoomApiClient zoomApiClient;
    private final OAuthStateService stateService;
    private final TokenCipher tokenCipher;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public ZoomConferencingOAuthService(ZoomConferencingConnectionRepository repository,
                                        ZoomApiClient zoomApiClient,
                                        OAuthStateService stateService,
                                        TokenCipher tokenCipher,
                                        @Value("${zoom.oauth.client-id:}") String clientId,
                                        @Value("${zoom.oauth.client-secret:}") String clientSecret,
                                        @Value("${zoom.oauth.redirect-uri:http://localhost:8080/integrations/conferencing/zoom/callback}") String redirectUri) {
        this.repository = repository;
        this.zoomApiClient = zoomApiClient;
        this.stateService = stateService;
        this.tokenCipher = tokenCipher;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public ConferencingProviderType providerType() {
        return ConferencingProviderType.ZOOM;
    }

    @Override
    public ConferencingProviderCapabilities capabilities() {
        return ConferencingProviderCapabilities.standalone();
    }

    @Override
    public String buildConnectUrl(UUID userId, String source, String returnTo, String bookingSessionId) {
        Assert.hasText(clientId, "Zoom clientId must not be empty");
        String state = stateService.generate(userId,
                source == null || source.isBlank() ? OAuthStateService.SOURCE_DASHBOARD : source,
                returnTo,
                bookingSessionId);
        return "https://zoom.us/oauth/authorize"
                + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(state);
    }

    @Override
    @Transactional
    public CallbackResult handleCallback(String code, String state) {
        OAuthStatePayload payload = stateService.validateAndExtract(state);
        UUID userId = payload.userId();
        if (userId == null) {
            throw new OAuthStateException(OAuthStateException.Reason.MISSING_USER, "OAuth state missing userId");
        }
        ZoomApiClient.OAuthTokenExchangeResult token = zoomApiClient.exchangeCodeForToken(code, redirectUri, clientId, clientSecret);
        String providerUserId = zoomApiClient.fetchProviderUserId(token.accessToken());

        ZoomConferencingConnection connection = repository.findByUserId(userId).orElseGet(ZoomConferencingConnection::new);
        connection.setUserId(userId);
        connection.setProviderUserId(providerUserId == null || providerUserId.isBlank() ? "zoom-user" : providerUserId);
        connection.setRefreshTokenCiphertext(tokenCipher.encrypt(token.refreshToken()));
        connection.setLastTokenExpiresAt(token.expiresAt().isBefore(Instant.now()) ? Instant.now().plusSeconds(300) : token.expiresAt());
        connection.setStatus(ConferencingConnectionStatus.ACTIVE);
        connection.setLastErrorCode(null);
        connection.setLastErrorAt(null);
        repository.save(connection);
        return new CallbackResult(payload.source(), payload.returnTo(), payload.bookingSessionId());
    }

    @Override
    @Transactional(readOnly = true)
    public String status(UUID userId) {
        return repository.findByUserId(userId)
                .map(c -> switch (c.getStatus()) {
                    case ACTIVE -> "CONNECTED";
                    case REVOKED, DISCONNECTED -> "DISCONNECTED";
                    default -> "ERROR";
                })
                .orElse("NOT_CONNECTED");
    }

    @Override
    @Transactional
    public void disconnect(UUID userId) {
        Optional<ZoomConferencingConnection> existing = repository.findByUserId(userId);
        if (existing.isEmpty()) {
            return;
        }
        ZoomConferencingConnection connection = existing.get();
        try {
            String refreshToken = tokenCipher.decrypt(connection.getRefreshTokenCiphertext());
            zoomApiClient.revokeToken(refreshToken);
        } catch (RuntimeException ignored) {
        }
        connection.setStatus(ConferencingConnectionStatus.REVOKED);
        repository.save(connection);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
