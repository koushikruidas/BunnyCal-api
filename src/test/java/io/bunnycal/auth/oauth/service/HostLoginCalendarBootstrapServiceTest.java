package io.bunnycal.auth.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.onboarding.OnboardingService;
import io.bunnycal.calendar.client.OAuthTokenExchangeResult;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.service.CalendarOAuthService;
import io.bunnycal.calendar.service.MicrosoftCalendarOAuthService;
import io.bunnycal.calendar.service.MissingRefreshTokenException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

@ExtendWith(MockitoExtension.class)
class HostLoginCalendarBootstrapServiceTest {
    private static final String GOOGLE_EVENTS = "https://www.googleapis.com/auth/calendar.events";
    private static final String GOOGLE_READ = "https://www.googleapis.com/auth/calendar.readonly";

    @Mock CalendarConnectionRepository connectionRepository;
    @Mock CalendarOAuthService googleOAuthService;
    @Mock MicrosoftCalendarOAuthService microsoftOAuthService;
    @Mock OnboardingService onboardingService;

    private HostLoginCalendarBootstrapService service;

    @BeforeEach
    void setUp() {
        service = new HostLoginCalendarBootstrapService(
                connectionRepository, googleOAuthService, microsoftOAuthService, onboardingService);
    }

    @Test
    void googleLoginCreatesConnectionAndSelectsDefaults() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)).thenReturn(Optional.empty());
        when(googleOAuthService.connectAuthorizedUser(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(OAuthTokenExchangeResult.class)))
                .thenReturn(new CalendarOAuthService.ConnectedCalendar(
                        connectionId, CalendarConnectionStatus.ACTIVE));

        service.bootstrapIfNeeded(userId, "google", authorizedClient(
                "google", Set.of("email", "profile", GOOGLE_EVENTS, GOOGLE_READ)));

        verify(googleOAuthService).connectAuthorizedUser(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(OAuthTokenExchangeResult.class));
        verify(onboardingService).configureCalendar(userId, connectionId);
        verify(microsoftOAuthService, never()).connectAuthorizedUser(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void existingActiveWritebackIsNeverRepointedByLogin() {
        UUID userId = UUID.randomUUID();
        CalendarConnection existing = mock(CalendarConnection.class);
        when(existing.getStatus()).thenReturn(CalendarConnectionStatus.ACTIVE);
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId))
                .thenReturn(Optional.of(existing));

        service.bootstrapIfNeeded(userId, "google", authorizedClient(
                "google", Set.of("email", "profile", GOOGLE_EVENTS, GOOGLE_READ)));

        verify(googleOAuthService, never()).connectAuthorizedUser(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(onboardingService, never()).configureCalendar(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void microsoftLoginUsesTheSameAutomaticDefaultSelection() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)).thenReturn(Optional.empty());
        when(microsoftOAuthService.connectAuthorizedUser(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(OAuthTokenExchangeResult.class)))
                .thenReturn(new CalendarOAuthService.ConnectedCalendar(
                        connectionId, CalendarConnectionStatus.ACTIVE));

        service.bootstrapIfNeeded(userId, "microsoft", authorizedClient(
                "microsoft", Set.of("openid", "profile", "Calendars.ReadWrite")));

        verify(microsoftOAuthService).connectAuthorizedUser(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(OAuthTokenExchangeResult.class));
        verify(onboardingService).configureCalendar(userId, connectionId);
    }

    @Test
    void missingCalendarConsentLeavesRecoveryToOnboarding() {
        UUID userId = UUID.randomUUID();
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bootstrapIfNeeded(
                userId, "google", authorizedClient("google", Set.of("email", "profile"))))
                .isInstanceOf(CalendarBootstrapUnavailableException.class)
                .hasMessageContaining("permission was not granted");

        verify(googleOAuthService, never()).connectAuthorizedUser(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * Google returns a refresh token only on first consent. A repeat login must still bootstrap
     * from the token already stored for that account instead of demanding re-consent.
     */
    @Test
    void repeatLoginWithoutRefreshTokenStillBootstraps() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)).thenReturn(Optional.empty());
        when(googleOAuthService.connectAuthorizedUser(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(OAuthTokenExchangeResult.class)))
                .thenReturn(new CalendarOAuthService.ConnectedCalendar(
                        connectionId, CalendarConnectionStatus.ACTIVE));

        service.bootstrapIfNeeded(userId, "google", authorizedClientWithoutRefreshToken("google"));

        ArgumentCaptor<OAuthTokenExchangeResult> token = ArgumentCaptor.forClass(OAuthTokenExchangeResult.class);
        verify(googleOAuthService).connectAuthorizedUser(
                org.mockito.ArgumentMatchers.eq(userId), token.capture());
        // The null is passed through so the provider service can reuse the stored ciphertext.
        assertThat(token.getValue().refreshToken()).isNull();
        verify(onboardingService).configureCalendar(userId, connectionId);
    }

    /** No token in the response and none on record: the user genuinely has to re-consent. */
    @Test
    void repeatLoginWithNoStoredTokenAsksForReconnect() {
        UUID userId = UUID.randomUUID();
        when(connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)).thenReturn(Optional.empty());
        when(googleOAuthService.connectAuthorizedUser(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(OAuthTokenExchangeResult.class)))
                .thenThrow(new MissingRefreshTokenException("Refresh token missing from provider response"));

        assertThatThrownBy(() -> service.bootstrapIfNeeded(
                userId, "google", authorizedClientWithoutRefreshToken("google")))
                .isInstanceOf(CalendarBootstrapUnavailableException.class)
                .hasMessageContaining("offline calendar access");
    }

    private static OAuth2AuthorizedClient authorizedClientWithoutRefreshToken(String registrationId) {
        OAuth2AuthorizedClient full = authorizedClient(
                registrationId, Set.of("email", "profile", GOOGLE_EVENTS, GOOGLE_READ));
        return new OAuth2AuthorizedClient(
                full.getClientRegistration(), full.getPrincipalName(), full.getAccessToken(), null);
    }

    private static OAuth2AuthorizedClient authorizedClient(String registrationId, Set<String> scopes) {
        Instant issuedAt = Instant.now();
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://provider.example/authorize")
                .tokenUri("https://provider.example/token")
                .userInfoUri("https://provider.example/userinfo")
                .userNameAttributeName("sub")
                .clientName(registrationId)
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "access-token",
                issuedAt,
                issuedAt.plusSeconds(3600),
                scopes);
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken("refresh-token", issuedAt);
        return new OAuth2AuthorizedClient(registration, "provider-user", accessToken, refreshToken);
    }
}
