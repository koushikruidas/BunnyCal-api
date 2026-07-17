package io.bunnycal.auth.oauth.service;

import io.bunnycal.auth.onboarding.OnboardingService;
import io.bunnycal.calendar.client.OAuthTokenExchangeResult;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.service.CalendarOAuthService;
import io.bunnycal.calendar.service.MicrosoftCalendarOAuthService;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.stereotype.Service;

/**
 * Converts the provider authorization already granted during host login into BunnyCal's durable
 * calendar connection. Guest and admin OAuth flows never call this service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostLoginCalendarBootstrapService {
    private static final String GOOGLE_EVENTS = "https://www.googleapis.com/auth/calendar.events";
    private static final String GOOGLE_READ = "https://www.googleapis.com/auth/calendar.readonly";
    private static final String MICROSOFT_READ_WRITE = "Calendars.ReadWrite";

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarOAuthService googleOAuthService;
    private final MicrosoftCalendarOAuthService microsoftOAuthService;
    private final OnboardingService onboardingService;

    /**
     * Does not replace an existing active writeback choice. A later login may use a different
     * provider account, and silently repointing live booking links would be destructive.
     */
    public void bootstrapIfNeeded(UUID userId, String registrationId, OAuth2AuthorizedClient client) {
        if (connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)
                .filter(connection -> connection.getStatus() == CalendarConnectionStatus.ACTIVE)
                .isPresent()) {
            log.debug("host_login_calendar_bootstrap_skipped userId={} reason=active_writeback", userId);
            return;
        }
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalArgumentException("Provider did not return an authorized calendar client");
        }

        OAuth2AccessToken accessToken = client.getAccessToken();
        requireCalendarScopes(registrationId, accessToken.getScopes());
        String refreshToken = client.getRefreshToken() == null
                ? null
                : client.getRefreshToken().getTokenValue();
        Instant expiresAt = accessToken.getExpiresAt();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("Provider did not return offline calendar access");
        }

        OAuthTokenExchangeResult token = new OAuthTokenExchangeResult(
                accessToken.getTokenValue(), refreshToken, expiresAt);
        CalendarOAuthService.ConnectedCalendar connected;
        if ("google".equalsIgnoreCase(registrationId)) {
            connected = googleOAuthService.connectAuthorizedUser(userId, token);
        } else if ("microsoft".equalsIgnoreCase(registrationId)) {
            connected = microsoftOAuthService.connectAuthorizedUser(userId, token);
        } else {
            throw new IllegalArgumentException("Unsupported host calendar provider: " + registrationId);
        }

        if (connected.status() != CalendarConnectionStatus.ACTIVE) {
            throw new IllegalStateException("Calendar initialization did not become active");
        }
        onboardingService.configureCalendar(userId, connected.connectionId());
        log.info("host_login_calendar_bootstrapped userId={} provider={} connectionId={}",
                userId, registrationId, connected.connectionId());
    }

    private static void requireCalendarScopes(String registrationId, Set<String> scopes) {
        Set<String> granted = scopes == null ? Set.of() : scopes;
        if ("google".equalsIgnoreCase(registrationId)) {
            if (!granted.contains(GOOGLE_EVENTS) || !granted.contains(GOOGLE_READ)) {
                throw new IllegalArgumentException("Google calendar permission was not granted");
            }
            return;
        }
        if ("microsoft".equalsIgnoreCase(registrationId)) {
            boolean canReadWrite = granted.stream()
                    .map(scope -> scope.toLowerCase(Locale.ROOT))
                    .anyMatch(scope -> scope.equals(MICROSOFT_READ_WRITE.toLowerCase(Locale.ROOT))
                            || scope.endsWith("/calendars.readwrite"));
            if (!canReadWrite) {
                throw new IllegalArgumentException("Microsoft calendar permission was not granted");
            }
            return;
        }
        throw new IllegalArgumentException("Unsupported host calendar provider: " + registrationId);
    }
}
