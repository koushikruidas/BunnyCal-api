package com.daedalussystems.easySchedule.integration;

import com.daedalussystems.easySchedule.auth.domain.identity.AuthIdentity;
import com.daedalussystems.easySchedule.auth.repository.AuthIdentityRepository;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.conferencing.service.ZoomConferencingOAuthService;
import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProviderCatalogService {
    private static final String VERSION = "v1alpha-provider-catalog";
    private static final String LIFECYCLE_AUTHORITY_APPLICATION = "application";
    private static final String GOOGLE = "google";
    private static final String MICROSOFT = "microsoft";
    private static final String ZOOM = "zoom";
    private static final String GOOGLE_MEET = "google_meet";
    private static final String MICROSOFT_TEAMS = "microsoft_teams";
    private static final String CUSTOM_URL = "custom_url";

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

        String identityProvider = resolveIdentityProvider(userId);
        String zoomStatus = zoomConferencingOAuthService.status(userId);
        boolean zoomConnected = "CONNECTED".equals(zoomStatus);
        List<String> conferencingProviders = zoomConnected ? List.of(ZOOM) : List.of();

        ProviderAuthoritySummary authoritySummary = new ProviderAuthoritySummary(
                identityProvider,
                availabilityProviders,
                LIFECYCLE_AUTHORITY_APPLICATION,
                conferencingProviders
        );

        List<ProviderDescriptor> providers = new ArrayList<>();
        providers.add(calendarProviderDescriptor(userId, CalendarProviderType.GOOGLE, authoritySummary));
        providers.add(calendarProviderDescriptor(userId, CalendarProviderType.MICROSOFT, authoritySummary));
        providers.add(zoomDescriptor(zoomStatus, authoritySummary));
        providers.add(googleMeetDescriptor(authoritySummary));
        providers.add(microsoftTeamsDescriptor(authoritySummary));
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
        ProviderLifecycleSourceOfTruth lifecycleSourceOfTruth = providerType == CalendarProviderType.GOOGLE
                ? ProviderLifecycleSourceOfTruth.WEBHOOK_AND_POLL
                : ProviderLifecycleSourceOfTruth.WEBHOOK_AND_POLL;
        ProviderRoleAssignments roles = new ProviderRoleAssignments(
                providerId.equals(authoritySummary.identityProvider()),
                authoritySummary.availabilityProviders().contains(providerId),
                false
        );
        return new ProviderDescriptor(
                providerId,
                ProviderType.HYBRID,
                flags,
                lifecycleSourceOfTruth,
                new ProviderStatusView(status, connected, actionRequired),
                roles,
                Map.of("calendarProviderType", providerType.name())
        );
    }

    private ProviderDescriptor zoomDescriptor(String zoomStatus, ProviderAuthoritySummary authoritySummary) {
        boolean connected = "CONNECTED".equals(zoomStatus);
        ProviderCapabilityFlags flags = new ProviderCapabilityFlags(
                false, false, false, true, true, false, false, false, true, false, false
        );
        return new ProviderDescriptor(
                ZOOM,
                ProviderType.CONFERENCING,
                flags,
                ProviderLifecycleSourceOfTruth.NONE,
                new ProviderStatusView(zoomStatus, connected, "ERROR".equals(zoomStatus)),
                new ProviderRoleAssignments(
                        false,
                        false,
                        authoritySummary.conferencingProviders().contains(ZOOM)
                ),
                Map.of("conferencingProviderType", ConferencingProviderType.ZOOM.name())
        );
    }

    private static ProviderDescriptor googleMeetDescriptor(ProviderAuthoritySummary authoritySummary) {
        ProviderCapabilityFlags flags = new ProviderCapabilityFlags(
                false, false, false, true, false, false, false, false, true, false, false
        );
        return new ProviderDescriptor(
                GOOGLE_MEET,
                ProviderType.CONFERENCING,
                flags,
                ProviderLifecycleSourceOfTruth.NONE,
                new ProviderStatusView(null, false, false),
                new ProviderRoleAssignments(false, false, authoritySummary.conferencingProviders().contains(GOOGLE_MEET)),
                Map.of("conferencingProviderType", ConferencingProviderType.GOOGLE_MEET.name())
        );
    }

    private static ProviderDescriptor customUrlDescriptor(ProviderAuthoritySummary authoritySummary) {
        ProviderCapabilityFlags flags = new ProviderCapabilityFlags(
                false, false, false, true, false, false, false, false, false, false, false
        );
        return new ProviderDescriptor(
                CUSTOM_URL,
                ProviderType.CONFERENCING,
                flags,
                ProviderLifecycleSourceOfTruth.NONE,
                new ProviderStatusView(null, false, false),
                new ProviderRoleAssignments(false, false, authoritySummary.conferencingProviders().contains(CUSTOM_URL)),
                Map.of("conferencingProviderType", ConferencingProviderType.CUSTOM_URL.name())
        );
    }

    private static ProviderDescriptor microsoftTeamsDescriptor(ProviderAuthoritySummary authoritySummary) {
        ProviderCapabilityFlags flags = new ProviderCapabilityFlags(
                false, false, false, true, false, false, false, false, true, false, false
        );
        return new ProviderDescriptor(
                MICROSOFT_TEAMS,
                ProviderType.CONFERENCING,
                flags,
                ProviderLifecycleSourceOfTruth.NONE,
                new ProviderStatusView(null, false, false),
                new ProviderRoleAssignments(false, false, authoritySummary.conferencingProviders().contains(MICROSOFT_TEAMS)),
                Map.of("conferencingProviderType", ConferencingProviderType.MICROSOFT_TEAMS.name())
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
