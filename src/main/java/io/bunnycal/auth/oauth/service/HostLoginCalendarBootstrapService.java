package io.bunnycal.auth.oauth.service;

import io.bunnycal.auth.onboarding.OnboardingService;
import io.bunnycal.calendar.client.OAuthTokenExchangeResult;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.service.CalendarOAuthService;
import io.bunnycal.calendar.service.MicrosoftCalendarOAuthService;
import io.bunnycal.calendar.service.MissingRefreshTokenException;
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
        boolean hasActiveWriteback = connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)
                .filter(connection -> connection.getStatus() == CalendarConnectionStatus.ACTIVE)
                .isPresent();
        // Skipping requires that the calendar is actually USABLE, not merely present.
        //
        // Testing only for an active writeback connection stranded anyone whose first login
        // connected the account but failed to finish selecting a calendar: the connection existed,
        // so every subsequent login skipped straight past this and never retried, while onboarding
        // kept reporting calendarReady=false and showing the reconnect notice forever. The only
        // escape was clicking Reconnect, which a user with a seemingly-connected calendar has no
        // reason to do.
        //
        // calendarReady() asks the question the skip actually depends on — is there an active
        // writeback connection with a selected, readable, writable calendar — so a half-configured
        // account now self-heals on the user's next login.
        if (hasActiveWriteback && onboardingService.get(userId).calendarReady()) {
            log.debug("host_login_calendar_bootstrap_skipped userId={} reason=calendar_ready", userId);
            return;
        }
        if (hasActiveWriteback) {
            log.info("host_login_calendar_bootstrap_retrying userId={} reason=writeback_present_but_calendar_not_ready",
                    userId);
        }
        if (client == null || client.getAccessToken() == null) {
            throw new CalendarBootstrapUnavailableException(
                    "Provider did not return an authorized calendar client");
        }

        OAuth2AccessToken accessToken = client.getAccessToken();
        requireCalendarScopes(registrationId, accessToken.getScopes());
        // Google issues a refresh token only on a user's first consent; later logins send
        // `prompt=select_account` and return none. That is normal, not a failure: the provider
        // services carry the stored token forward once the token exchange has identified the
        // external account. Rejecting a null here would preempt that and push healthy repeat
        // logins into the onboarding reconnect notice.
        String refreshToken = client.getRefreshToken() == null
                ? null
                : client.getRefreshToken().getTokenValue();
        Instant expiresAt = accessToken.getExpiresAt();

        OAuthTokenExchangeResult token = new OAuthTokenExchangeResult(
                accessToken.getTokenValue(), refreshToken, expiresAt);
        CalendarOAuthService.ConnectedCalendar connected;
        try {
            if ("google".equalsIgnoreCase(registrationId)) {
                connected = googleOAuthService.connectAuthorizedUser(userId, token);
            } else if ("microsoft".equalsIgnoreCase(registrationId)) {
                connected = microsoftOAuthService.connectAuthorizedUser(userId, token);
            } else {
                throw new IllegalArgumentException("Unsupported host calendar provider: " + registrationId);
            }
        } catch (MissingRefreshTokenException ex) {
            // No token in the response and none stored for this account: the user has to re-consent.
            // Expected and recoverable, so surface it as such rather than as a fault.
            throw new CalendarBootstrapUnavailableException(
                    "Provider did not return offline calendar access and none is stored");
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
                throw new CalendarBootstrapUnavailableException("Google calendar permission was not granted");
            }
            return;
        }
        if ("microsoft".equalsIgnoreCase(registrationId)) {
            boolean canReadWrite = granted.stream()
                    .map(scope -> scope.toLowerCase(Locale.ROOT))
                    .anyMatch(scope -> scope.equals(MICROSOFT_READ_WRITE.toLowerCase(Locale.ROOT))
                            || scope.endsWith("/calendars.readwrite"));
            if (!canReadWrite) {
                throw new CalendarBootstrapUnavailableException("Microsoft calendar permission was not granted");
            }
            return;
        }
        throw new IllegalArgumentException("Unsupported host calendar provider: " + registrationId);
    }
}
