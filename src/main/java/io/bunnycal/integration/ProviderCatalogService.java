package io.bunnycal.integration;

import io.bunnycal.auth.domain.identity.AuthIdentity;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.conferencing.service.ZoomConferencingOAuthService;
import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.common.enums.ConferencingProviderType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProviderCatalogService {
    private static final Logger log = LoggerFactory.getLogger(ProviderCatalogService.class);
    private static final String VERSION = "v1alpha-provider-catalog";
    private static final String LIFECYCLE_AUTHORITY_APPLICATION = "application";
    private static final String GOOGLE = "google";
    private static final String MICROSOFT = "microsoft";
    private static final String ZOOM = "zoom";
    private static final String GOOGLE_MEET = "google_meet";
    private static final String MICROSOFT_TEAMS = "microsoft_teams";
    private static final String CUSTOM_URL = "custom_url";
    // Parent-integration identifiers that the capability-derived conferencing
    // providers report via their lifecycleAuthority field.
    private static final String GOOGLE_CALENDAR_AUTHORITY = "google_calendar";
    private static final String MICROSOFT_CALENDAR_AUTHORITY = "microsoft_calendar";

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final ZoomConferencingOAuthService zoomConferencingOAuthService;
    private final AuthIdentityRepository authIdentityRepository;

    public ProviderCatalogService(CalendarConnectionRepository calendarConnectionRepository,
                                  ZoomConferencingOAuthService zoomConferencingOAuthService,
                                  AuthIdentityRepository authIdentityRepository) {
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.zoomConferencingOAuthService = zoomConferencingOAuthService;
        this.authIdentityRepository = authIdentityRepository;
    }

    public ProviderCatalogResponse catalogForUser(UUID userId) {
        List<CalendarConnection> activeCalendarConnections = calendarConnectionRepository
                .findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE);
        List<String> availabilityProviders = activeCalendarConnections.stream()
                .map(CalendarConnection::getProvider)
                .map(this::calendarProviderId)
                .distinct()
                .toList();

        boolean googleCalendarConnected = availabilityProviders.contains(GOOGLE);
        boolean microsoftCalendarConnected = availabilityProviders.contains(MICROSOFT);

        String identityProvider = resolveIdentityProvider(userId);
        String zoomStatus = zoomConferencingOAuthService.status(userId);
        boolean zoomConnected = "CONNECTED".equals(zoomStatus);

        // Conferencing-provider authority is now derived from the underlying calendar
        // identities, not from standalone OAuth state. Google Meet rides on Google
        // Calendar; Teams rides on Microsoft Calendar. Without the parent integration
        // these capabilities are not usable, so they must not appear in the authority
        // list either.
        List<String> conferencingProviders = new ArrayList<>();
        if (zoomConnected) conferencingProviders.add(ZOOM);
        if (googleCalendarConnected) conferencingProviders.add(GOOGLE_MEET);
        if (microsoftCalendarConnected) conferencingProviders.add(MICROSOFT_TEAMS);

        ProviderAuthoritySummary authoritySummary = new ProviderAuthoritySummary(
                identityProvider,
                availabilityProviders,
                LIFECYCLE_AUTHORITY_APPLICATION,
                List.copyOf(conferencingProviders)
        );

        List<ProviderDescriptor> providers = new ArrayList<>();
        providers.add(calendarProviderDescriptor(userId, CalendarProviderType.GOOGLE, authoritySummary));
        providers.add(calendarProviderDescriptor(userId, CalendarProviderType.MICROSOFT, authoritySummary));
        providers.add(zoomDescriptor(zoomStatus, authoritySummary));
        providers.add(googleMeetDescriptor(googleCalendarConnected, authoritySummary));
        providers.add(microsoftTeamsDescriptor(microsoftCalendarConnected, authoritySummary));
        providers.add(customUrlDescriptor(authoritySummary));

        return new ProviderCatalogResponse(VERSION, providers, authoritySummary);
    }

    public Map<String, ProviderDescriptor> calendarProviderSubset(UUID userId) {
        ProviderCatalogResponse response = catalogForUser(userId);
        Map<String, ProviderDescriptor> map = new LinkedHashMap<>();
        response.providers().stream()
                .filter(p -> p.providerType() == ProviderType.CALENDAR || p.providerType() == ProviderType.HYBRID)
                .forEach(p -> map.put(p.providerId(), p));
        return map;
    }

    public Map<String, ProviderDescriptor> conferencingProviderSubset(UUID userId) {
        ProviderCatalogResponse response = catalogForUser(userId);
        Map<String, ProviderDescriptor> map = new LinkedHashMap<>();
        response.providers().stream()
                .filter(p -> p.providerType() == ProviderType.CONFERENCING)
                .forEach(p -> map.put(p.providerId(), p));
        return map;
    }

    private ProviderDescriptor calendarProviderDescriptor(UUID userId,
                                                          CalendarProviderType providerType,
                                                          ProviderAuthoritySummary authoritySummary) {
        String providerId = calendarProviderId(providerType);
        Optional<CalendarConnection> connection = calendarConnectionRepository.findByUserIdAndProvider(userId, providerType);
        String status = connection.map(c -> mapCalendarStatus(c.getStatus())).orElse("NOT_CONNECTED");
        boolean connected = "CONNECTED".equals(status);
        boolean actionRequired = "ERROR".equals(status);
        ProviderCapabilityFlags flags = switch (providerType) {
            case GOOGLE -> new ProviderCapabilityFlags(
                    true, true, true, true, true, false, true, true, false, true, true
            );
            case MICROSOFT -> new ProviderCapabilityFlags(
                    true, true, true, true, true, false, true, true, true, true, true
            );
        };
        ProviderRoleAssignments roles = new ProviderRoleAssignments(
                providerId.equals(authoritySummary.identityProvider()),
                authoritySummary.availabilityProviders().contains(providerId),
                false
        );
        return new ProviderDescriptor(
                providerId,
                ProviderType.HYBRID,
                flags,
                ProviderLifecycleSourceOfTruth.WEBHOOK_AND_POLL,
                null,
                ProviderStatusView.standalone(status, connected, actionRequired),
                roles,
                Map.of("calendarProviderType", providerType.name())
        );
    }

    private ProviderDescriptor zoomDescriptor(String zoomStatus, ProviderAuthoritySummary authoritySummary) {
        boolean connected = "CONNECTED".equals(zoomStatus);
        ProviderCapabilityFlags flags = new ProviderCapabilityFlags(
                false, false, false, true, true, false, false, false, true, false, false
        );
        log.info("conferencing_provider_status provider={} standaloneOAuth={} derivedCapability={}",
                ZOOM, true, false);
        return new ProviderDescriptor(
                ZOOM,
                ProviderType.CONFERENCING,
                flags,
                ProviderLifecycleSourceOfTruth.NONE,
                null,
                ProviderStatusView.standalone(zoomStatus, connected, "ERROR".equals(zoomStatus)),
                new ProviderRoleAssignments(
                        false,
                        false,
                        authoritySummary.conferencingProviders().contains(ZOOM)
                ),
                Map.of("conferencingProviderType", ConferencingProviderType.ZOOM.name())
        );
    }

    private static ProviderDescriptor googleMeetDescriptor(boolean googleCalendarConnected,
                                                           ProviderAuthoritySummary authoritySummary) {
        ProviderCapabilityFlags flags = new ProviderCapabilityFlags(
                false, false, false, true, false, false, false, false, true, false, false
        );
        log.info("conferencing_capability_derived provider={} lifecycleSource={} available={}",
                GOOGLE_MEET, GOOGLE_CALENDAR_AUTHORITY, googleCalendarConnected);
        log.info("conferencing_provider_status provider={} standaloneOAuth={} derivedCapability={}",
                GOOGLE_MEET, false, true);
        ProviderStatusView status = ProviderStatusView.derived(
                googleCalendarConnected ? "CONNECTED" : "NOT_CONNECTED",
                googleCalendarConnected,
                GOOGLE);
        return new ProviderDescriptor(
                GOOGLE_MEET,
                ProviderType.CONFERENCING,
                flags,
                ProviderLifecycleSourceOfTruth.NONE,
                GOOGLE_CALENDAR_AUTHORITY,
                status,
                new ProviderRoleAssignments(false, false, authoritySummary.conferencingProviders().contains(GOOGLE_MEET)),
                Map.of("conferencingProviderType", ConferencingProviderType.GOOGLE_MEET.name())
        );
    }

    private static ProviderDescriptor microsoftTeamsDescriptor(boolean microsoftCalendarConnected,
                                                               ProviderAuthoritySummary authoritySummary) {
        ProviderCapabilityFlags flags = new ProviderCapabilityFlags(
                false, false, false, true, false, false, false, false, true, false, false
        );
        log.info("conferencing_capability_derived provider={} lifecycleSource={} available={}",
                MICROSOFT_TEAMS, MICROSOFT_CALENDAR_AUTHORITY, microsoftCalendarConnected);
        log.info("conferencing_provider_status provider={} standaloneOAuth={} derivedCapability={}",
                MICROSOFT_TEAMS, false, true);
        ProviderStatusView status = ProviderStatusView.derived(
                microsoftCalendarConnected ? "CONNECTED" : "NOT_CONNECTED",
                microsoftCalendarConnected,
                MICROSOFT);
        return new ProviderDescriptor(
                MICROSOFT_TEAMS,
                ProviderType.CONFERENCING,
                flags,
                ProviderLifecycleSourceOfTruth.NONE,
                MICROSOFT_CALENDAR_AUTHORITY,
                status,
                new ProviderRoleAssignments(false, false, authoritySummary.conferencingProviders().contains(MICROSOFT_TEAMS)),
                Map.of("conferencingProviderType", ConferencingProviderType.MICROSOFT_TEAMS.name())
        );
    }

    private static ProviderDescriptor customUrlDescriptor(ProviderAuthoritySummary authoritySummary) {
        ProviderCapabilityFlags flags = new ProviderCapabilityFlags(
                false, false, false, true, false, false, false, false, false, false, false
        );
        // Custom URL is a manual user-entered field, not a backed integration. It is
        // "available" whenever the conferencing feature itself is available, which is
        // always — there's no lifecycle to mirror.
        return new ProviderDescriptor(
                CUSTOM_URL,
                ProviderType.CONFERENCING,
                flags,
                ProviderLifecycleSourceOfTruth.NONE,
                null,
                new ProviderStatusView(null, false, false, true, null),
                new ProviderRoleAssignments(false, false, authoritySummary.conferencingProviders().contains(CUSTOM_URL)),
                Map.of("conferencingProviderType", ConferencingProviderType.CUSTOM_URL.name())
        );
    }

    private String resolveIdentityProvider(UUID userId) {
        return authIdentityRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .max(Comparator.comparing(AuthIdentity::getCreatedAt))
                .map(AuthIdentity::getProvider)
                .map(this::authProviderId)
                .orElse(null);
    }

    private String authProviderId(AuthProvider provider) {
        return provider == AuthProvider.MICROSOFT ? MICROSOFT : GOOGLE;
    }

    private String calendarProviderId(CalendarProviderType providerType) {
        return providerType == CalendarProviderType.MICROSOFT ? MICROSOFT : GOOGLE;
    }

    private static String mapCalendarStatus(CalendarConnectionStatus status) {
        if (status == CalendarConnectionStatus.ACTIVE) return "CONNECTED";
        if (status == CalendarConnectionStatus.REVOKED || status == CalendarConnectionStatus.DISCONNECTED) return "DISCONNECTED";
        if (status == CalendarConnectionStatus.PENDING || status == CalendarConnectionStatus.SYNCING) return "PENDING";
        return "ERROR";
    }
}
