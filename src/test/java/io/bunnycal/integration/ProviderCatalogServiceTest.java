package io.bunnycal.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.conferencing.service.ZoomConferencingOAuthService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderCatalogServiceTest {

    private CalendarConnectionRepository connectionRepo;
    private ZoomConferencingOAuthService zoom;
    private AuthIdentityRepository authIdentityRepository;
    private ProviderCatalogService service;

    @BeforeEach
    void setUp() {
        connectionRepo = mock(CalendarConnectionRepository.class);
        zoom = mock(ZoomConferencingOAuthService.class);
        authIdentityRepository = mock(AuthIdentityRepository.class);
        service = new ProviderCatalogService(connectionRepo, zoom, authIdentityRepository);

        when(authIdentityRepository.findByUserIdOrderByCreatedAtDesc(any(UUID.class))).thenReturn(List.of());
    }

    @Test
    void googleMeet_isDerivedFromGoogleCalendar_whenCalendarConnected() {
        UUID userId = UUID.randomUUID();
        CalendarConnection google = new CalendarConnection();
        google.setProvider(CalendarProviderType.GOOGLE);
        google.setStatus(CalendarConnectionStatus.ACTIVE);
        when(connectionRepo.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(google));
        when(connectionRepo.findByUserIdAndProviderOrderByCreatedAtAsc(userId, CalendarProviderType.GOOGLE))
                .thenReturn(List.of(google));
        when(connectionRepo.findByUserIdAndProviderOrderByCreatedAtAsc(userId, CalendarProviderType.MICROSOFT))
                .thenReturn(List.of());
        when(zoom.status(userId)).thenReturn("NOT_CONNECTED");

        Map<String, ProviderDescriptor> conferencing = service.conferencingProviderSubset(userId);
        ProviderDescriptor meet = conferencing.get("google_meet");

        assertThat(meet).isNotNull();
        assertThat(meet.lifecycleAuthority()).isEqualTo("google_calendar");
        assertThat(meet.status().connectionStatus()).isEqualTo("CONNECTED");
        assertThat(meet.status().isConnected()).isTrue();
        assertThat(meet.status().isAvailable()).isTrue();
        assertThat(meet.status().derivedFromProvider()).isEqualTo("google");

        // Authority summary now lists Meet because the underlying calendar is connected.
        assertThat(service.catalogForUser(userId).authority().conferencingProviders())
                .contains("google_meet");
    }

    @Test
    void googleMeet_isUnavailable_whenGoogleCalendarMissing() {
        UUID userId = UUID.randomUUID();
        when(connectionRepo.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of());
        when(connectionRepo.findByUserIdAndProviderOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());
        when(zoom.status(userId)).thenReturn("NOT_CONNECTED");

        ProviderDescriptor meet = service.conferencingProviderSubset(userId).get("google_meet");

        assertThat(meet.status().isConnected()).isFalse();
        assertThat(meet.status().isAvailable()).isFalse();
        assertThat(meet.status().connectionStatus()).isEqualTo("NOT_CONNECTED");
        assertThat(meet.status().derivedFromProvider()).isEqualTo("google");
        assertThat(meet.lifecycleAuthority()).isEqualTo("google_calendar");
    }

    @Test
    void microsoftTeams_isDerivedFromMicrosoftCalendar_whenCalendarConnected() {
        UUID userId = UUID.randomUUID();
        CalendarConnection ms = new CalendarConnection();
        ms.setProvider(CalendarProviderType.MICROSOFT);
        ms.setStatus(CalendarConnectionStatus.ACTIVE);
        when(connectionRepo.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(ms));
        when(connectionRepo.findByUserIdAndProviderOrderByCreatedAtAsc(userId, CalendarProviderType.MICROSOFT))
                .thenReturn(List.of(ms));
        when(connectionRepo.findByUserIdAndProviderOrderByCreatedAtAsc(userId, CalendarProviderType.GOOGLE))
                .thenReturn(List.of());
        when(zoom.status(userId)).thenReturn("NOT_CONNECTED");

        ProviderDescriptor teams = service.conferencingProviderSubset(userId).get("microsoft_teams");

        assertThat(teams.lifecycleAuthority()).isEqualTo("microsoft_calendar");
        assertThat(teams.status().isConnected()).isTrue();
        assertThat(teams.status().isAvailable()).isTrue();
        assertThat(teams.status().derivedFromProvider()).isEqualTo("microsoft");
    }

    @Test
    void zoom_semanticsAreStandaloneAndUnchanged() {
        UUID userId = UUID.randomUUID();
        when(connectionRepo.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of());
        when(connectionRepo.findByUserIdAndProviderOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());
        when(zoom.status(userId)).thenReturn("CONNECTED");

        ProviderDescriptor zoomDescriptor = service.conferencingProviderSubset(userId).get("zoom");

        assertThat(zoomDescriptor.lifecycleAuthority()).isNull();
        assertThat(zoomDescriptor.status().connectionStatus()).isEqualTo("CONNECTED");
        assertThat(zoomDescriptor.status().isConnected()).isTrue();
        assertThat(zoomDescriptor.status().isAvailable()).isTrue();
        assertThat(zoomDescriptor.status().derivedFromProvider()).isNull();
    }
}
