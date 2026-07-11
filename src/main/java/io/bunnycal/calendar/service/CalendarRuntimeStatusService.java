package io.bunnycal.calendar.service;

import io.bunnycal.auth.domain.identity.AuthIdentity;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeOrchestrationJsonCodec;
import io.bunnycal.availability.service.EventTypeOrchestrationNormalizer.AvailabilityBinding;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.MicrosoftAccountClassifier;
import io.bunnycal.calendar.dto.CalendarRuntimeStatusResponse;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.integration.ProviderCapabilities;
import io.bunnycal.integration.ProviderCapabilityRegistry;
import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.conferencing.service.ZoomConferencingOAuthService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CalendarRuntimeStatusService {
    private static final Logger log = LoggerFactory.getLogger(CalendarRuntimeStatusService.class);
    private static final String LIFECYCLE_AUTHORITY_APPLICATION = "application";
    // Anything older than this triggers a best-effort inventory refresh inline on /status.
    private static final Duration INVENTORY_STALE_AFTER = Duration.ofHours(6);

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarConnectionCalendarRepository inventoryRepository;
    private final ProviderCapabilityRegistry providerCapabilityRegistry;
    private final ZoomConferencingOAuthService zoomConferencingOAuthService;
    private final AuthIdentityRepository authIdentityRepository;
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final EventTypeOrchestrationJsonCodec orchestrationJsonCodec;
    private final CalendarInventoryHydrator inventoryHydrator;

    public CalendarRuntimeStatusService(CalendarConnectionRepository calendarConnectionRepository,
                                        CalendarConnectionCalendarRepository inventoryRepository,
                                        ProviderCapabilityRegistry providerCapabilityRegistry,
                                        ZoomConferencingOAuthService zoomConferencingOAuthService,
                                        AuthIdentityRepository authIdentityRepository,
                                        UserRepository userRepository,
                                        EventTypeRepository eventTypeRepository,
                                        EventTypeOrchestrationJsonCodec orchestrationJsonCodec,
                                        CalendarInventoryHydrator inventoryHydrator) {
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.inventoryRepository = inventoryRepository;
        this.providerCapabilityRegistry = providerCapabilityRegistry;
        this.zoomConferencingOAuthService = zoomConferencingOAuthService;
        this.authIdentityRepository = authIdentityRepository;
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.orchestrationJsonCodec = orchestrationJsonCodec;
        this.inventoryHydrator = inventoryHydrator;
    }

    public CalendarRuntimeStatusResponse runtimeStatus(UUID userId) {
        List<CalendarConnection> connections = calendarConnectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE);
        CalendarRuntimeStatusResponse.Identity identity = resolveIdentity(userId);

        SelectionIndex selection = buildSelectionIndex(userId, connections);

        // Trigger best-effort refresh for stale inventory BEFORE we read it back.
        refreshStaleInventories(connections);

        Map<UUID, List<CalendarConnectionCalendar>> inventoryByConnection = loadInventory(connections);

        List<CalendarRuntimeStatusResponse.ConnectionStatus> connectionStatuses = connections.stream()
                .map(c -> toConnectionStatus(c, inventoryByConnection.getOrDefault(c.getId(), List.of()), selection))
                .toList();

        boolean googleConnected = connections.stream().anyMatch(c -> c.getProvider() == CalendarProviderType.GOOGLE);
        boolean microsoftTeamsCapable = connections.stream().anyMatch(this::supportsNativeTeamsForConnection);
        boolean zoomConnected = "CONNECTED".equalsIgnoreCase(zoomConferencingOAuthService.status(userId));

        return new CalendarRuntimeStatusResponse(
                LIFECYCLE_AUTHORITY_APPLICATION,
                identity,
                connectionStatuses,
                new CalendarRuntimeStatusResponse.Conferencing(
                        zoomConnected,
                        googleConnected,
                        microsoftTeamsCapable
                )
        );
    }

    private void refreshStaleInventories(List<CalendarConnection> connections) {
        Instant cutoff = Instant.now().minus(INVENTORY_STALE_AFTER);
        for (CalendarConnection connection : connections) {
            if (connection.getId() == null) continue;
            List<CalendarConnectionCalendar> rows = inventoryRepository
                    .findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connection.getId());
            boolean stale = rows.isEmpty()
                    || rows.stream().anyMatch(r -> r.getLastSyncedAt() == null || r.getLastSyncedAt().isBefore(cutoff));
            if (stale) {
                inventoryHydrator.hydrateBestEffort(connection);
            }
        }
    }

    private Map<UUID, List<CalendarConnectionCalendar>> loadInventory(List<CalendarConnection> connections) {
        List<UUID> ids = connections.stream().map(CalendarConnection::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return inventoryRepository
                .findByConnectionIdInOrderByConnectionIdAscPrimaryDescExternalCalendarIdAsc(ids).stream()
                .collect(Collectors.groupingBy(CalendarConnectionCalendar::getConnectionId));
    }

    private SelectionIndex buildSelectionIndex(UUID userId, List<CalendarConnection> connections) {
        Set<UUID> activeConnectionIds = connections.stream()
                .map(CalendarConnection::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<CalendarKey> availability = new HashSet<>();
        Set<CalendarKey> projection = new HashSet<>();

        List<EventType> eventTypes = eventTypeRepository.findByUserIdAndDeletedAtIsNullOrderByNameAsc(userId);
        for (EventType eventType : eventTypes) {
            if (eventType.getProjectionConnectionId() != null
                    && eventType.getProjectionCalendarId() != null
                    && !eventType.getProjectionCalendarId().isBlank()) {
                projection.add(new CalendarKey(eventType.getProjectionConnectionId(), eventType.getProjectionCalendarId()));
            }
            if (eventType.getAvailabilityCalendarsJson() == null || eventType.getAvailabilityCalendarsJson().isBlank()) {
                continue;
            }
            List<AvailabilityBinding> bindings = orchestrationJsonCodec
                    .deserializeAvailabilityBindings(eventType.getAvailabilityCalendarsJson());
            for (AvailabilityBinding binding : bindings) {
                if (binding == null || binding.connectionId() == null) {
                    continue;
                }
                if (binding.externalCalendarId() != null && !binding.externalCalendarId().isBlank()) {
                    availability.add(new CalendarKey(binding.connectionId(), binding.externalCalendarId()));
                }
                // Diagnostics: legacy rows where the frontend stored connectionId as the calendarId.
                if (binding.externalCalendarId() != null
                        && binding.connectionId().toString().equals(binding.externalCalendarId())) {
                    log.warn("invalid_calendar_mapping connectionId={} externalCalendarId={} reason=connection_id_used_as_calendar_id eventTypeId={} userId={}",
                            binding.connectionId(),
                            binding.externalCalendarId(),
                            eventType.getId(),
                            userId);
                }
            }
        }
        return new SelectionIndex(availability, projection, activeConnectionIds);
    }

    private CalendarRuntimeStatusResponse.ConnectionStatus toConnectionStatus(CalendarConnection connection,
                                                                              List<CalendarConnectionCalendar> inventory,
                                                                              SelectionIndex selection) {
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
        List<CalendarRuntimeStatusResponse.Calendar> calendars = inventory.stream()
                .filter(c -> !c.isHidden())
                .map(c -> new CalendarRuntimeStatusResponse.Calendar(
                        c.getExternalCalendarId(),
                        c.getName(),
                        c.isPrimary(),
                        c.isCanRead(),
                        c.isCanWrite(),
                        selection.isAvailability(connection.getId(), c.getExternalCalendarId()),
                        selection.isProjection(connection.getId(), c.getExternalCalendarId())
                ))
                .toList();
        // A connection is a linked account, not necessarily the login identity — a user can
        // connect several accounts per provider. The email captured at connect-time is what
        // distinguishes them in the UI, so it is authoritative wherever we have it. Falling back
        // to the login user's email is only correct for pre-V118_0 rows connected before the
        // email was captured, and only for a user's *first* Google connection; the migration
        // backfills those, so in practice the fallback should never fire.
        String displayName = connection.getProviderUserId();
        String email = connection.getProviderUserId();
        if (connection.getAccountEmail() != null && !connection.getAccountEmail().isBlank()) {
            displayName = connection.getAccountEmail();
            email = connection.getAccountEmail();
        } else if (connection.getProvider() == CalendarProviderType.GOOGLE) {
            Optional<User> user = userRepository.findById(connection.getUserId());
            displayName = user.map(User::getName).orElse(displayName);
            email = user.map(User::getEmail).orElse(email);
        }
        return new CalendarRuntimeStatusResponse.ConnectionStatus(
                connection.getId() == null ? null : connection.getId().toString(),
                connection.getProvider() == null ? null : connection.getProvider().name().toLowerCase(Locale.ROOT),
                displayName,
                email,
                mapCalendarStatus(connection.getStatus()),
                isActionRequired(connection.getStatus()),
                connection.isDefaultWriteback(),
                capabilityView,
                roles,
                toAccountMetadata(connection),
                calendars
        );
    }

    private CalendarRuntimeStatusResponse.Account toAccountMetadata(CalendarConnection connection) {
        if (connection == null || connection.getProvider() != CalendarProviderType.MICROSOFT) {
            return null;
        }
        boolean personal = MicrosoftAccountClassifier.isConsumerMsa(connection);
        return new CalendarRuntimeStatusResponse.Account(
                personal ? "PERSONAL_MSA" : "MICROSOFT_365",
                !personal
        );
    }

    private boolean supportsNativeTeamsForConnection(CalendarConnection connection) {
        return connection != null
                && connection.getProvider() == CalendarProviderType.MICROSOFT
                && !MicrosoftAccountClassifier.isConsumerMsa(connection);
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

    private record CalendarKey(UUID connectionId, String externalCalendarId) {}

    private record SelectionIndex(Set<CalendarKey> availability,
                                  Set<CalendarKey> projection,
                                  Set<UUID> activeConnectionIds) {
        boolean isAvailability(UUID connectionId, String externalCalendarId) {
            return availability.contains(new CalendarKey(connectionId, externalCalendarId));
        }
        boolean isProjection(UUID connectionId, String externalCalendarId) {
            return projection.contains(new CalendarKey(connectionId, externalCalendarId));
        }
    }
}
