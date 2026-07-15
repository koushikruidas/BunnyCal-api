package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.CalendarRole;
import io.bunnycal.calendar.dto.CalendarRuntimeStatusResponse;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.conferencing.service.ZoomConferencingOAuthService;
import io.bunnycal.integration.ProviderCapabilities;
import io.bunnycal.integration.ProviderCapabilityRegistry;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarRuntimeStatusServiceTest {

    @Test
    void runtimeStatus_exposesInventoryAndItsPerCalendarFlags() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        String primaryCalendarId = "AQMkADAwATM";
        String secondaryCalendarId = "AQMkBC";

        CalendarConnection connection = new CalendarConnection();
        setId(connection, connectionId);
        connection.setUserId(userId);
        connection.setProvider(CalendarProviderType.MICROSOFT);
        connection.setProviderUserId("user@outlook.com");
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        CalendarConnectionRepository connectionRepo = mock(CalendarConnectionRepository.class);
        when(connectionRepo.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(connection));

        CalendarConnectionCalendar primary = new CalendarConnectionCalendar();
        primary.setConnectionId(connectionId);
        primary.setExternalCalendarId(primaryCalendarId);
        primary.setName("Calendar");
        primary.setPrimary(true);
        primary.setCalendarRole(CalendarRole.PRIMARY);
        primary.setCanRead(true);
        primary.setCanWrite(true);
        // Both flags are now properties of the inventory row itself: this calendar blocks
        // availability, and it is the write-back destination within this connection.
        primary.setChecksAvailability(true);
        primary.setSelected(true);
        primary.setSupportsNativeTeams(true);
        primary.setLastSyncedAt(Instant.now());

        CalendarConnectionCalendar secondary = new CalendarConnectionCalendar();
        secondary.setConnectionId(connectionId);
        secondary.setExternalCalendarId(secondaryCalendarId);
        secondary.setName("Engineering");
        secondary.setCalendarRole(CalendarRole.OTHER);
        secondary.setCanRead(true);
        secondary.setCanWrite(false);
        secondary.setChecksAvailability(false);
        secondary.setSelected(false);
        secondary.setLastSyncedAt(Instant.now());

        CalendarConnectionCalendar hidden = new CalendarConnectionCalendar();
        hidden.setConnectionId(connectionId);
        hidden.setExternalCalendarId("AQ-hidden");
        hidden.setName("Hidden");
        hidden.setCalendarRole(CalendarRole.OTHER);
        hidden.setHidden(true);
        hidden.setLastSyncedAt(Instant.now());

        CalendarConnectionCalendarRepository inventoryRepo = mock(CalendarConnectionCalendarRepository.class);
        when(inventoryRepo.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of(primary, secondary, hidden));
        when(inventoryRepo.findByConnectionIdInOrderByConnectionIdAscPrimaryDescExternalCalendarIdAsc(anyList()))
                .thenReturn(List.of(primary, secondary, hidden));

        ProviderCapabilityRegistry capabilityRegistry = mock(ProviderCapabilityRegistry.class);
        when(capabilityRegistry.forCalendar(CalendarProviderType.MICROSOFT))
                .thenReturn(new ProviderCapabilities(true, true, true, true, true));

        ZoomConferencingOAuthService zoom = mock(ZoomConferencingOAuthService.class);
        when(zoom.status(userId)).thenReturn("DISCONNECTED");

        AuthIdentityRepository authIdentityRepository = mock(AuthIdentityRepository.class);
        when(authIdentityRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.empty());

        CalendarInventoryHydrator hydrator = mock(CalendarInventoryHydrator.class);

        CalendarRuntimeStatusService service = new CalendarRuntimeStatusService(
                connectionRepo, inventoryRepo, capabilityRegistry, zoom,
                authIdentityRepository, userRepository, hydrator);

        CalendarRuntimeStatusResponse response = service.runtimeStatus(userId);

        assertThat(response.connections()).hasSize(1);
        CalendarRuntimeStatusResponse.ConnectionStatus status = response.connections().get(0);

        // Backward-compat: legacy fields untouched.
        assertThat(status.connectionId()).isEqualTo(connectionId.toString());
        assertThat(status.provider()).isEqualTo("microsoft");
        assertThat(status.status()).isEqualTo("CONNECTED");
        assertThat(status.capabilities().availability()).isTrue();
        assertThat(status.roles().availabilityEligible()).isTrue();
        assertThat(status.account()).isNotNull();
        assertThat(status.account().type()).isEqualTo("MICROSOFT_365");
        assertThat(status.account().supportsNativeTeams()).isTrue();

        // Only the primary is exposed. Holiday, birthday, feed, and secondary rows stay implicit.
        assertThat(status.calendars()).hasSize(1);

        CalendarRuntimeStatusResponse.Calendar primaryOut = status.calendars().stream()
                .filter(c -> c.calendarId().equals(primaryCalendarId)).findFirst().orElseThrow();
        assertThat(primaryOut.isPrimary()).isTrue();
        assertThat(primaryOut.canWrite()).isTrue();
        assertThat(primaryOut.selectedForAvailability()).isTrue();
        assertThat(primaryOut.selectedForProjection()).isTrue();

        assertThat(status.calendars()).noneMatch(c -> c.calendarId().equals(secondaryCalendarId));

        // Fresh inventory means no stale-refresh trigger.
        verify(hydrator, never()).hydrateBestEffort(any());
    }

    @Test
    void runtimeStatus_triggersBestEffortHydrateWhenInventoryIsEmpty() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        CalendarConnection connection = new CalendarConnection();
        setId(connection, connectionId);
        connection.setUserId(userId);
        connection.setProvider(CalendarProviderType.GOOGLE);
        connection.setProviderUserId("user@gmail.com");
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        CalendarConnectionRepository connectionRepo = mock(CalendarConnectionRepository.class);
        when(connectionRepo.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(connection));

        CalendarConnectionCalendarRepository inventoryRepo = mock(CalendarConnectionCalendarRepository.class);
        when(inventoryRepo.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of());
        when(inventoryRepo.findByConnectionIdInOrderByConnectionIdAscPrimaryDescExternalCalendarIdAsc(anyList()))
                .thenReturn(List.of());

        ProviderCapabilityRegistry capabilityRegistry = mock(ProviderCapabilityRegistry.class);
        when(capabilityRegistry.forCalendar(CalendarProviderType.GOOGLE))
                .thenReturn(new ProviderCapabilities(true, true, true, true, true));
        ZoomConferencingOAuthService zoom = mock(ZoomConferencingOAuthService.class);
        when(zoom.status(userId)).thenReturn("DISCONNECTED");
        AuthIdentityRepository authIdentityRepository = mock(AuthIdentityRepository.class);
        when(authIdentityRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.empty());
        CalendarInventoryHydrator hydrator = mock(CalendarInventoryHydrator.class);

        CalendarRuntimeStatusService service = new CalendarRuntimeStatusService(
                connectionRepo, inventoryRepo, capabilityRegistry, zoom,
                authIdentityRepository, userRepository, hydrator);

        service.runtimeStatus(userId);

        verify(hydrator).hydrateBestEffort(connection);
    }

    @Test
    void runtimeStatus_googleConnection_usesUserDisplayNameAndEmail_notProviderSubjectId() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        CalendarConnection connection = new CalendarConnection();
        setId(connection, connectionId);
        connection.setUserId(userId);
        connection.setProvider(CalendarProviderType.GOOGLE);
        connection.setProviderUserId("110128961967336207135");
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        CalendarConnectionRepository connectionRepo = mock(CalendarConnectionRepository.class);
        when(connectionRepo.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(connection));

        CalendarConnectionCalendarRepository inventoryRepo = mock(CalendarConnectionCalendarRepository.class);
        when(inventoryRepo.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of());
        when(inventoryRepo.findByConnectionIdInOrderByConnectionIdAscPrimaryDescExternalCalendarIdAsc(anyList()))
                .thenReturn(List.of());

        ProviderCapabilityRegistry capabilityRegistry = mock(ProviderCapabilityRegistry.class);
        when(capabilityRegistry.forCalendar(CalendarProviderType.GOOGLE))
                .thenReturn(new ProviderCapabilities(true, true, true, true, true));
        ZoomConferencingOAuthService zoom = mock(ZoomConferencingOAuthService.class);
        when(zoom.status(userId)).thenReturn("DISCONNECTED");
        AuthIdentityRepository authIdentityRepository = mock(AuthIdentityRepository.class);
        when(authIdentityRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        UserRepository userRepository = mock(UserRepository.class);
        User user = new User();
        user.setId(userId);
        user.setName("Koushik Ruidas");
        user.setEmail("koushikruidas@gmail.com");
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        CalendarInventoryHydrator hydrator = mock(CalendarInventoryHydrator.class);

        CalendarRuntimeStatusService service = new CalendarRuntimeStatusService(
                connectionRepo, inventoryRepo, capabilityRegistry, zoom,
                authIdentityRepository, userRepository, hydrator);

        CalendarRuntimeStatusResponse response = service.runtimeStatus(userId);
        CalendarRuntimeStatusResponse.ConnectionStatus status = response.connections().get(0);

        assertThat(status.displayName()).isEqualTo("Koushik Ruidas");
        assertThat(status.email()).isEqualTo("koushikruidas@gmail.com");
        assertThat(status.account()).isNull();
    }

    @Test
    void runtimeStatus_consumerMsa_marksAccountPersonalAndDisablesTeams() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        CalendarConnection connection = new CalendarConnection();
        setId(connection, connectionId);
        connection.setUserId(userId);
        connection.setProvider(CalendarProviderType.MICROSOFT);
        connection.setProviderUserId("ed9adb1ac97c0819");
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        CalendarConnectionRepository connectionRepo = mock(CalendarConnectionRepository.class);
        when(connectionRepo.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(connection));

        CalendarConnectionCalendarRepository inventoryRepo = mock(CalendarConnectionCalendarRepository.class);
        when(inventoryRepo.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of());
        when(inventoryRepo.findByConnectionIdInOrderByConnectionIdAscPrimaryDescExternalCalendarIdAsc(anyList()))
                .thenReturn(List.of());

        ProviderCapabilityRegistry capabilityRegistry = mock(ProviderCapabilityRegistry.class);
        when(capabilityRegistry.forCalendar(CalendarProviderType.MICROSOFT))
                .thenReturn(new ProviderCapabilities(true, true, true, true, true));
        ZoomConferencingOAuthService zoom = mock(ZoomConferencingOAuthService.class);
        when(zoom.status(userId)).thenReturn("DISCONNECTED");
        AuthIdentityRepository authIdentityRepository = mock(AuthIdentityRepository.class);
        when(authIdentityRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.empty());
        CalendarInventoryHydrator hydrator = mock(CalendarInventoryHydrator.class);

        CalendarRuntimeStatusService service = new CalendarRuntimeStatusService(
                connectionRepo, inventoryRepo, capabilityRegistry, zoom,
                authIdentityRepository, userRepository, hydrator);

        CalendarRuntimeStatusResponse response = service.runtimeStatus(userId);
        CalendarRuntimeStatusResponse.ConnectionStatus status = response.connections().get(0);
        assertThat(status.account()).isNotNull();
        assertThat(status.account().type()).isEqualTo("PERSONAL_MSA");
        assertThat(status.account().supportsNativeTeams()).isFalse();
        assertThat(response.conferencing().teamsAvailable()).isFalse();
    }

    @Test
    void runtimeStatus_microsoft365_marksAccountOrgAndEnablesTeams() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        CalendarConnection connection = new CalendarConnection();
        setId(connection, connectionId);
        connection.setUserId(userId);
        connection.setProvider(CalendarProviderType.MICROSOFT);
        connection.setProviderUserId("12345678-1234-1234-1234-123456789012");
        connection.setStatus(CalendarConnectionStatus.ACTIVE);

        CalendarConnectionRepository connectionRepo = mock(CalendarConnectionRepository.class);
        when(connectionRepo.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(connection));

        CalendarConnectionCalendar teamsCalendar = new CalendarConnectionCalendar();
        teamsCalendar.setConnectionId(connectionId);
        teamsCalendar.setExternalCalendarId("primary");
        teamsCalendar.setPrimary(true);
        teamsCalendar.setSupportsNativeTeams(true);
        teamsCalendar.setLastSyncedAt(Instant.now());

        CalendarConnectionCalendarRepository inventoryRepo = mock(CalendarConnectionCalendarRepository.class);
        when(inventoryRepo.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of(teamsCalendar));
        when(inventoryRepo.findByConnectionIdInOrderByConnectionIdAscPrimaryDescExternalCalendarIdAsc(anyList()))
                .thenReturn(List.of(teamsCalendar));

        ProviderCapabilityRegistry capabilityRegistry = mock(ProviderCapabilityRegistry.class);
        when(capabilityRegistry.forCalendar(CalendarProviderType.MICROSOFT))
                .thenReturn(new ProviderCapabilities(true, true, true, true, true));
        ZoomConferencingOAuthService zoom = mock(ZoomConferencingOAuthService.class);
        when(zoom.status(userId)).thenReturn("DISCONNECTED");
        AuthIdentityRepository authIdentityRepository = mock(AuthIdentityRepository.class);
        when(authIdentityRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.empty());
        CalendarInventoryHydrator hydrator = mock(CalendarInventoryHydrator.class);

        CalendarRuntimeStatusService service = new CalendarRuntimeStatusService(
                connectionRepo, inventoryRepo, capabilityRegistry, zoom,
                authIdentityRepository, userRepository, hydrator);

        CalendarRuntimeStatusResponse response = service.runtimeStatus(userId);
        CalendarRuntimeStatusResponse.ConnectionStatus status = response.connections().get(0);
        assertThat(status.account()).isNotNull();
        assertThat(status.account().type()).isEqualTo("MICROSOFT_365");
        assertThat(status.account().supportsNativeTeams()).isTrue();
        assertThat(response.conferencing().teamsAvailable()).isTrue();
    }

    private static void setId(CalendarConnection connection, UUID id) {
        try {
            Field idField = CalendarConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(connection, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
