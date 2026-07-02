package io.bunnycal.conferencing.service;

import io.bunnycal.calendar.auth.OAuthStateException;
import io.bunnycal.calendar.auth.OAuthStatePayload;
import io.bunnycal.calendar.auth.OAuthStateService;
import io.bunnycal.calendar.auth.TokenCipher;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.client.ZoomApiClient;
import io.bunnycal.conferencing.domain.ConferencingConnectionStatus;
import io.bunnycal.conferencing.domain.ZoomConferencingConnection;
import io.bunnycal.conferencing.repository.ZoomConferencingConnectionRepository;
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
                                        @Value("${zoom.oauth.redirect-uri:http://127.0.0.1:8080/integrations/conferencing/zoom/callback}") String redirectUri) {
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
        // Data-deletion guarantee for Zoom marketplace compliance: no token
        // material or provider identifiers are retained after disconnect.
        repository.delete(connection);
    }

    /**
     * Handles the Zoom {@code app_deauthorized} marketplace event: the user removed
     * the app from their Zoom account, so Zoom has already invalidated the tokens.
     * All stored connection data for that Zoom user must be deleted (Zoom requires
     * deletion within 10 days of deauthorization).
     */
    @Transactional
    public void handleDeauthorized(String providerUserId) {
        if (providerUserId == null || providerUserId.isBlank()) {
            return;
        }
        var connections = repository.findAllByProviderUserId(providerUserId);
        if (!connections.isEmpty()) {
            repository.deleteAll(connections);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
