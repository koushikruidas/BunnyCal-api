package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeOrchestrationJsonCodec;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
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
    void runtimeStatus_exposesInventoryAndComputesSelectionUnion() {
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
        primary.setCanRead(true);
        primary.setCanWrite(true);
        primary.setLastSyncedAt(Instant.now());

        CalendarConnectionCalendar secondary = new CalendarConnectionCalendar();
        secondary.setConnectionId(connectionId);
        secondary.setExternalCalendarId(secondaryCalendarId);
        secondary.setName("Engineering");
        secondary.setCanRead(true);
        secondary.setCanWrite(false);
        secondary.setLastSyncedAt(Instant.now());

        CalendarConnectionCalendar hidden = new CalendarConnectionCalendar();
        hidden.setConnectionId(connectionId);
        hidden.setExternalCalendarId("AQ-hidden");
        hidden.setName("Hidden");
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

        EventType eventType = new EventType();
        eventType.setId(UUID.randomUUID());
        eventType.setUserId(userId);
        // Availability binding references the primary calendar of this connection.
        // The secondary is NOT bound; the legacy entry uses connectionId as calendarId.
        String legacyConnectionAsCalendarId = connectionId.toString();
        String availabilityJson = "[{\"connectionId\":\"" + connectionId + "\",\"provider\":\"microsoft\",\"externalCalendarId\":\""
                + primaryCalendarId + "\"},"
                + "{\"connectionId\":\"" + connectionId + "\",\"provider\":\"microsoft\",\"externalCalendarId\":\""
                + legacyConnectionAsCalendarId + "\"}]";
        eventType.setAvailabilityCalendarsJson(availabilityJson);
        // Projection destination points to the primary calendar.
        eventType.setProjectionConnectionId(connectionId);
        eventType.setProjectionCalendarId(primaryCalendarId);
        eventType.setProjectionProvider(CalendarProviderType.MICROSOFT);

        EventTypeRepository eventTypeRepository = mock(EventTypeRepository.class);
        when(eventTypeRepository.findByUserIdOrderByNameAsc(userId)).thenReturn(List.of(eventType));

        EventTypeOrchestrationJsonCodec codec = new EventTypeOrchestrationJsonCodec(new ObjectMapper());
        CalendarInventoryHydrator hydrator = mock(CalendarInventoryHydrator.class);

        CalendarRuntimeStatusService service = new CalendarRuntimeStatusService(
                connectionRepo, inventoryRepo, capabilityRegistry, zoom,
                authIdentityRepository, userRepository, eventTypeRepository, codec, hydrator);

        CalendarRuntimeStatusResponse response = service.runtimeStatus(userId);

        assertThat(response.connections()).hasSize(1);
        CalendarRuntimeStatusResponse.ConnectionStatus status = response.connections().get(0);

        // Backward-compat: legacy fields untouched.
        assertThat(status.connectionId()).isEqualTo(connectionId.toString());
        assertThat(status.provider()).isEqualTo("microsoft");
        assertThat(status.status()).isEqualTo("CONNECTED");
        assertThat(status.capabilities().availability()).isTrue();
        assertThat(status.roles().availabilityEligible()).isTrue();

        // Additive: calendars hydrated, hidden filtered.
        assertThat(status.calendars()).hasSize(2);

        CalendarRuntimeStatusResponse.Calendar primaryOut = status.calendars().stream()
                .filter(c -> c.calendarId().equals(primaryCalendarId)).findFirst().orElseThrow();
        assertThat(primaryOut.isPrimary()).isTrue();
        assertThat(primaryOut.canWrite()).isTrue();
        assertThat(primaryOut.selectedForAvailability()).isTrue();
        assertThat(primaryOut.selectedForProjection()).isTrue();

        CalendarRuntimeStatusResponse.Calendar secondaryOut = status.calendars().stream()
                .filter(c -> c.calendarId().equals(secondaryCalendarId)).findFirst().orElseThrow();
        assertThat(secondaryOut.canWrite()).isFalse();
        assertThat(secondaryOut.selectedForAvailability()).isFalse();
        assertThat(secondaryOut.selectedForProjection()).isFalse();

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
        EventTypeRepository eventTypeRepository = mock(EventTypeRepository.class);
        when(eventTypeRepository.findByUserIdOrderByNameAsc(userId)).thenReturn(List.of());
        EventTypeOrchestrationJsonCodec codec = new EventTypeOrchestrationJsonCodec(new ObjectMapper());
        CalendarInventoryHydrator hydrator = mock(CalendarInventoryHydrator.class);

        CalendarRuntimeStatusService service = new CalendarRuntimeStatusService(
                connectionRepo, inventoryRepo, capabilityRegistry, zoom,
                authIdentityRepository, userRepository, eventTypeRepository, codec, hydrator);

        service.runtimeStatus(userId);

        verify(hydrator).hydrateBestEffort(connection);
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
