package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.auth.OAuthStateService;
import com.daedalussystems.easySchedule.calendar.auth.TokenCipher;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import com.daedalussystems.easySchedule.calendar.client.OAuthTokenExchangeResult;
import com.daedalussystems.easySchedule.calendar.config.GoogleOAuthProperties;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
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

    public CalendarOAuthService(CalendarConnectionRepository repository,
                                GoogleApiClient googleApiClient,
                                GoogleOAuthProperties properties,
                                OAuthStateService stateService,
                                TokenCipher tokenCipher) {
        this.repository = repository;
        this.googleApiClient = googleApiClient;
        this.properties = properties;
        this.stateService = stateService;
        this.tokenCipher = tokenCipher;
    }

    public String buildGoogleConnectUrl(UUID userId) {
        String state = stateService.generate(userId);
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
    public void handleGoogleCallback(String code, String state) {
        UUID userId = stateService.validateAndExtractUserId(state);
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

        CalendarConnection connection = existing.orElseGet(CalendarConnection::new);
        String refreshTokenCiphertext;
        if (token.refreshToken() != null && !token.refreshToken().isBlank()) {
            refreshTokenCiphertext = tokenCipher.encrypt(token.refreshToken());
        } else if (connection.getRefreshTokenCiphertext() != null && !connection.getRefreshTokenCiphertext().isBlank()) {
            refreshTokenCiphertext = connection.getRefreshTokenCiphertext();
        } else {
            throw new IllegalArgumentException("Refresh token missing from provider response");
        }

        connection.setUserId(userId);
        connection.setProvider(GOOGLE_PROVIDER);
        connection.setProviderUserId(providerUserId);
        connection.setRefreshTokenCiphertext(refreshTokenCiphertext);
        connection.setLastTokenExpiresAt(token.expiresAt().isBefore(Instant.now()) ? Instant.now().plusSeconds(300) : token.expiresAt());
        connection.setScopes(properties.getScopes());
        connection.setStatus(CalendarConnectionStatus.ACTIVE);
        connection.setLastErrorCode(null);
        connection.setLastErrorAt(null);
        repository.save(connection);
        log.info("{{\"event\":\"calendar_connected\",\"userId\":\"{}\",\"provider\":\"GOOGLE\"}}", userId);
    }

    @Transactional(readOnly = true)
    public String googleConnectionStatus(UUID userId) {
        Optional<CalendarConnection> connection = repository.findByUserIdAndProvider(userId, GOOGLE_PROVIDER);
        if (connection.isEmpty()) {
            return "NOT_CONNECTED";
        }
        return connection.get().getStatus() == CalendarConnectionStatus.ACTIVE ? "CONNECTED" : "ERROR";
    }

    @Transactional
    public void disconnectGoogle(UUID userId) {
        Optional<CalendarConnection> existing = repository.findByUserIdAndProvider(userId, GOOGLE_PROVIDER);
        if (existing.isPresent()) {
            CalendarConnection connection = existing.get();
            connection.setStatus(CalendarConnectionStatus.REVOKED);
            repository.save(connection);
            log.info("{{\"event\":\"calendar_disconnected\",\"userId\":\"{}\",\"provider\":\"GOOGLE\"}}", userId);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
