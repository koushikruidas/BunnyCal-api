package io.bunnycal.calendar.service;

import io.bunnycal.auth.domain.identity.AuthIdentity;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.dto.CalendarRuntimeStatusResponse;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.integration.ProviderCapabilities;
import io.bunnycal.integration.ProviderCapabilityRegistry;
import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.conferencing.service.ZoomConferencingOAuthService;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CalendarRuntimeStatusService {
    private static final String LIFECYCLE_AUTHORITY_APPLICATION = "application";

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final ProviderCapabilityRegistry providerCapabilityRegistry;
    private final ZoomConferencingOAuthService zoomConferencingOAuthService;
    private final AuthIdentityRepository authIdentityRepository;
    private final UserRepository userRepository;

    public CalendarRuntimeStatusService(CalendarConnectionRepository calendarConnectionRepository,
                                        ProviderCapabilityRegistry providerCapabilityRegistry,
                                        ZoomConferencingOAuthService zoomConferencingOAuthService,
                                        AuthIdentityRepository authIdentityRepository,
                                        UserRepository userRepository) {
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.providerCapabilityRegistry = providerCapabilityRegistry;
        this.zoomConferencingOAuthService = zoomConferencingOAuthService;
        this.authIdentityRepository = authIdentityRepository;
        this.userRepository = userRepository;
    }

    public CalendarRuntimeStatusResponse runtimeStatus(UUID userId) {
        List<CalendarConnection> connections = calendarConnectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE);
        CalendarRuntimeStatusResponse.Identity identity = resolveIdentity(userId);
        List<CalendarRuntimeStatusResponse.ConnectionStatus> connectionStatuses = connections.stream()
                .map(this::toConnectionStatus)
                .toList();

        boolean googleConnected = connections.stream().anyMatch(c -> c.getProvider() == CalendarProviderType.GOOGLE);
        boolean microsoftConnected = connections.stream().anyMatch(c -> c.getProvider() == CalendarProviderType.MICROSOFT);
        boolean zoomConnected = "CONNECTED".equalsIgnoreCase(zoomConferencingOAuthService.status(userId));

        return new CalendarRuntimeStatusResponse(
                LIFECYCLE_AUTHORITY_APPLICATION,
                identity,
                connectionStatuses,
                new CalendarRuntimeStatusResponse.Conferencing(
                        zoomConnected,
                        googleConnected,
                        microsoftConnected
                )
        );
    }

    private CalendarRuntimeStatusResponse.ConnectionStatus toConnectionStatus(CalendarConnection connection) {
        ProviderCapabilities capabilities = providerCapabilityRegistry.forCalendar(connection.getProvider());
        CalendarRuntimeStatusResponse.Capabilities capabilityView = new CalendarRuntimeStatusResponse.Capabilities(
                capabilities != null && capabilities.supportsAvailabilitySync(),
                capabilities != null && (capabilities.supportsWebhooks() || capabilities.supportsPushRenewal()),
                capabilities != null && capabilities.supportsConferencing(),
                capabilities != null && capabilities.supportsWebhooks()
        );
        CalendarRuntimeStatusResponse.Roles roles = new CalendarRuntimeStatusResponse.Roles(
                capabilityView.availability(),
                capabilityView.projection(),
                capabilityView.conferencingProvisioning()
        );
        return new CalendarRuntimeStatusResponse.ConnectionStatus(
                connection.getId() == null ? null : connection.getId().toString(),
                connection.getProvider() == null ? null : connection.getProvider().name().toLowerCase(Locale.ROOT),
                connection.getProviderUserId(),
                connection.getProviderUserId(),
                mapCalendarStatus(connection.getStatus()),
                isActionRequired(connection.getStatus()),
                capabilityView,
                roles
        );
    }

    private CalendarRuntimeStatusResponse.Identity resolveIdentity(UUID userId) {
        String email = userRepository.findById(userId).map(User::getEmail).orElse(null);
        String provider = authIdentityRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .max(Comparator.comparing(AuthIdentity::getCreatedAt))
                .map(AuthIdentity::getProvider)
                .map(this::authProviderId)
                .orElse(null);
        return new CalendarRuntimeStatusResponse.Identity(provider, email);
    }

    private String authProviderId(AuthProvider provider) {
        return provider == AuthProvider.MICROSOFT ? "microsoft" : "google";
    }

    private static String mapCalendarStatus(CalendarConnectionStatus status) {
        if (status == CalendarConnectionStatus.ACTIVE) return "CONNECTED";
        if (status == CalendarConnectionStatus.REVOKED || status == CalendarConnectionStatus.DISCONNECTED) return "DISCONNECTED";
        if (status == CalendarConnectionStatus.PENDING || status == CalendarConnectionStatus.SYNCING) return "PENDING";
        return "ERROR";
    }

    private static boolean isActionRequired(CalendarConnectionStatus status) {
        return status == CalendarConnectionStatus.ERROR || status == CalendarConnectionStatus.REVOKED;
    }
}

