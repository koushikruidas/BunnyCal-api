package io.bunnycal.conferencing.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.calendar.auth.OAuthStateException;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.service.ConferencingOAuthService;
import io.bunnycal.conferencing.service.ConferencingOAuthServiceRegistry;
import io.bunnycal.conferencing.service.ConferencingProviderCapabilities;
import io.bunnycal.conferencing.service.GoogleMeetConferencingOAuthService;
import io.bunnycal.conferencing.service.ZoomConferencingOAuthService;
import io.bunnycal.integration.ProviderAuthoritySummary;
import io.bunnycal.integration.ProviderCapabilityFlags;
import io.bunnycal.integration.ProviderCatalogResponse;
import io.bunnycal.integration.ProviderCatalogService;
import io.bunnycal.integration.ProviderCapabilityRegistry;
import io.bunnycal.integration.ProviderDescriptor;
import io.bunnycal.integration.ProviderLifecycleSourceOfTruth;
import io.bunnycal.integration.ProviderRoleAssignments;
import io.bunnycal.integration.ProviderStatusView;
import io.bunnycal.integration.ProviderType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class ConferencingIntegrationControllerTest {
    @Mock
    private ZoomConferencingOAuthService zoomOAuthService;
    @Mock
    private GoogleMeetConferencingOAuthService googleMeetOAuthService;
    @Mock
    private ProviderCatalogService providerCatalogService;

    private ConferencingIntegrationController controller;

    @BeforeEach
    void setUp() {
        when(zoomOAuthService.providerType()).thenReturn(ConferencingProviderType.ZOOM);
        when(googleMeetOAuthService.providerType()).thenReturn(ConferencingProviderType.GOOGLE_MEET);
        ConferencingOAuthServiceRegistry registry =
                new ConferencingOAuthServiceRegistry(List.of(zoomOAuthService, googleMeetOAuthService));
        controller = new ConferencingIntegrationController(
                registry,
                new ProviderCapabilityRegistry(),
                providerCatalogService,
                new SimpleMeterRegistry(),
                "http://localhost:3000/error",
                "http://localhost:3000/success");
    }

    private static ProviderDescriptor zoomDescriptor(boolean connected) {
        return new ProviderDescriptor(
                "zoom",
                ProviderType.CONFERENCING,
                new ProviderCapabilityFlags(false, false, false, true, true, false, false, false, true, false, false),
                ProviderLifecycleSourceOfTruth.NONE,
                null,
                ProviderStatusView.standalone(connected ? "CONNECTED" : "NOT_CONNECTED", connected, false),
                new ProviderRoleAssignments(false, false, connected),
                Map.of("conferencingProviderType", "ZOOM"));
    }

    private static ProviderDescriptor googleMeetDescriptor(boolean googleCalendarConnected) {
        return new ProviderDescriptor(
                "google_meet",
                ProviderType.CONFERENCING,
                new ProviderCapabilityFlags(false, false, false, true, false, false, false, false, true, false, false),
                ProviderLifecycleSourceOfTruth.NONE,
                "google_calendar",
                ProviderStatusView.derived(
                        googleCalendarConnected ? "CONNECTED" : "NOT_CONNECTED",
                        googleCalendarConnected,
                        "google"),
                new ProviderRoleAssignments(false, false, googleCalendarConnected),
                Map.of("conferencingProviderType", "GOOGLE_MEET"));
    }

    @Test
    void connect_routesGoogleMeetThroughRegistry() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(googleMeetOAuthService.buildConnectUrl(eq(userId), any(), any(), any()))
                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?x=y");

        ResponseEntity<ApiResponse<Map<String, String>>> response =
                controller.connect("google_meet", auth, null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth?x=y",
                response.getBody().getData().get("redirectUrl"));
        verify(zoomOAuthService, never()).buildConnectUrl(any(), any(), any(), any());
    }

    @Test
    void connect_normalizesHyphenatedAndMixedCaseIdentifiers() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(googleMeetOAuthService.buildConnectUrl(eq(userId), any(), any(), any()))
                .thenReturn("https://example/auth");

        controller.connect("GOOGLE-MEET", auth, null, null, null);
        controller.connect("Google-Meet", auth, null, null, null);
        controller.connect("google_meet", auth, null, null, null);
        controller.connect("  google_meet  ", auth, null, null, null);

        verify(googleMeetOAuthService, times(4))
                .buildConnectUrl(eq(userId), any(), any(), any());
    }

    @Test
    void connect_zoomStillWorksForBackwardsCompatibility() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(zoomOAuthService.buildConnectUrl(eq(userId), any(), any(), any()))
                .thenReturn("https://zoom.us/oauth/authorize?x=y");

        ResponseEntity<ApiResponse<Map<String, String>>> response =
                controller.connect("ZOOM", auth, "dashboard", null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("https://zoom.us/oauth/authorize?x=y",
                response.getBody().getData().get("redirectUrl"));
    }

    @Test
    void connect_unknownProviderReturnsValidationError() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);

        ResponseEntity<ApiResponse<Map<String, String>>> response =
                controller.connect("skype", auth, null, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
    }

    @Test
    void callback_routesByNormalizedProvider() {
        when(zoomOAuthService.handleCallback("code", "state"))
                .thenReturn(new ConferencingOAuthService.CallbackResult("dashboard", null, null));

        ResponseEntity<Void> response = controller.callback("Zoom", "code", "state");

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals("http://localhost:3000/success?integrationSuccess=zoom",
                response.getHeaders().getLocation().toString());
    }

    @Test
    void callback_mapsExpiredStateToErrorRedirect() {
        when(zoomOAuthService.handleCallback("code", "state"))
                .thenThrow(new OAuthStateException(OAuthStateException.Reason.EXPIRED, "boom"));

        ResponseEntity<Void> response = controller.callback("zoom", "code", "state");

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertTrue(response.getHeaders().getLocation().toString()
                .contains("error=oauth_state_expired"));
    }

    @Test
    void callback_unknownProviderRedirectsToError() {
        ResponseEntity<Void> response = controller.callback("skype", "code", "state");

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertTrue(response.getHeaders().getLocation().toString()
                .contains("error=VALIDATION_ERROR"));
    }

    @Test
    void status_includesCapabilityMetadataPerProvider() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null);
        when(zoomOAuthService.capabilities()).thenReturn(ConferencingProviderCapabilities.standalone());
        when(googleMeetOAuthService.capabilities())
                .thenReturn(ConferencingProviderCapabilities.managedBy("google_calendar"));
        ProviderCatalogResponse response = new ProviderCatalogResponse(
                "v1alpha-provider-catalog",
                List.of(),
                new ProviderAuthoritySummary("google", List.of("google"), "application", List.of("zoom", "google_meet")));
        when(providerCatalogService.catalogForUser(userId)).thenReturn(response);
        when(providerCatalogService.conferencingProviderSubset(userId)).thenReturn(Map.of(
                "zoom", zoomDescriptor(true),
                "google_meet", googleMeetDescriptor(true)));

        ApiResponse<Map<String, Object>> body = controller.status(authentication).getBody();

        assertTrue(body.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> providers =
                (Map<String, Map<String, Object>>) body.getData().get("providers");

        Map<String, Object> zoom = providers.get("zoom");
        assertEquals("CONNECTED", zoom.get("status"));
        assertEquals(Boolean.TRUE, zoom.get("connected"));
        assertEquals("standalone", zoom.get("type"));
        assertEquals(Boolean.TRUE, zoom.get("standaloneOAuth"));
        assertEquals(Boolean.TRUE, zoom.get("disconnectSupported"));
        assertNull(zoom.get("managedBy"));

        Map<String, Object> meet = providers.get("google_meet");
        assertEquals("CONNECTED", meet.get("status"));
        assertEquals(Boolean.TRUE, meet.get("connected"));
        assertEquals("capability", meet.get("type"));
        assertEquals(Boolean.FALSE, meet.get("standaloneOAuth"));
        assertEquals(Boolean.FALSE, meet.get("disconnectSupported"));
        assertEquals("google_calendar", meet.get("managedBy"));

        assertNotNull(body.getData().get("capabilities"));
        assertNotNull(body.getData().get("providerCatalog"));
        assertNotNull(body.getData().get("authority"));
        // Deprecation surface advertised in the response body.
        @SuppressWarnings("unchecked")
        Map<String, String> deprecations =
                (Map<String, String>) body.getData().get("_deprecations");
        assertNotNull(deprecations);
        assertTrue(deprecations.get("providers").contains("providerCatalog"));
    }

    @Test
    void status_reflectsDisconnectedManagedProvider() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null);
        when(zoomOAuthService.capabilities()).thenReturn(ConferencingProviderCapabilities.standalone());
        when(googleMeetOAuthService.capabilities())
                .thenReturn(ConferencingProviderCapabilities.managedBy("google_calendar"));
        when(providerCatalogService.catalogForUser(userId)).thenReturn(new ProviderCatalogResponse(
                "v1alpha-provider-catalog", List.of(),
                new ProviderAuthoritySummary("google", List.of(), "application", List.of())));
        when(providerCatalogService.conferencingProviderSubset(userId)).thenReturn(Map.of(
                "zoom", zoomDescriptor(false),
                "google_meet", googleMeetDescriptor(false)));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> providers = (Map<String, Map<String, Object>>) controller.status(authentication)
                .getBody().getData().get("providers");

        assertEquals(Boolean.FALSE, providers.get("zoom").get("connected"));
        assertEquals(Boolean.FALSE, providers.get("google_meet").get("connected"));
    }

    @Test
    void status_legacyProvidersAreAStrictProjectionOfProviderCatalog() {
        // Locks in the post-refactor invariant: providers.* values MUST match
        // providerCatalog.* values. There is no independent code path that could drift.
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null);
        when(zoomOAuthService.capabilities()).thenReturn(ConferencingProviderCapabilities.standalone());
        when(googleMeetOAuthService.capabilities())
                .thenReturn(ConferencingProviderCapabilities.managedBy("google_calendar"));
        Map<String, ProviderDescriptor> catalog = Map.of(
                "zoom", zoomDescriptor(true),
                "google_meet", googleMeetDescriptor(false));
        when(providerCatalogService.conferencingProviderSubset(userId)).thenReturn(catalog);
        when(providerCatalogService.catalogForUser(userId)).thenReturn(new ProviderCatalogResponse(
                "v1alpha-provider-catalog", List.of(),
                new ProviderAuthoritySummary("google", List.of(), "application", List.of("zoom"))));

        ApiResponse<Map<String, Object>> body = controller.status(authentication).getBody();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> providers =
                (Map<String, Map<String, Object>>) body.getData().get("providers");

        for (Map.Entry<String, ProviderDescriptor> entry : catalog.entrySet()) {
            String providerId = entry.getKey();
            ProviderDescriptor descriptor = entry.getValue();
            Map<String, Object> legacy = providers.get(providerId);
            assertNotNull(legacy, "providerCatalog entry " + providerId + " missing from legacy projection");
            assertEquals(descriptor.status().connectionStatus(), legacy.get("status"),
                    "status drift for " + providerId);
            assertEquals(descriptor.status().isConnected(), legacy.get("connected"),
                    "connected drift for " + providerId);
        }
    }

    @Test
    void disconnect_zoomRevokesViaService() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(zoomOAuthService.capabilities()).thenReturn(ConferencingProviderCapabilities.standalone());

        ResponseEntity<ApiResponse<Void>> response = controller.disconnect("Zoom", auth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        verify(zoomOAuthService).disconnect(userId);
    }

    @Test
    void disconnect_unknownProviderReturnsValidationError() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);

        ResponseEntity<ApiResponse<Void>> response = controller.disconnect("skype", auth);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
    }

    @Test
    void disconnect_managedProviderReturnsConflictWithGuidance() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(googleMeetOAuthService.capabilities())
                .thenReturn(ConferencingProviderCapabilities.managedBy("google_calendar"));

        ResponseEntity<ApiResponse<Void>> response = controller.disconnect("google_meet", auth);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("CONFERENCING_DISCONNECT_NOT_SUPPORTED",
                response.getBody().getError().getCode());
        assertTrue(response.getBody().getError().getMessage().contains("google_calendar"));
        verify(googleMeetOAuthService, never()).disconnect(any());
    }

    @Test
    void disconnect_normalizesProviderIdBeforeCapabilityCheck() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(googleMeetOAuthService.capabilities())
                .thenReturn(ConferencingProviderCapabilities.managedBy("google_calendar"));

        ResponseEntity<ApiResponse<Void>> response = controller.disconnect("Google-Meet", auth);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(googleMeetOAuthService, never()).disconnect(any());
    }
}
