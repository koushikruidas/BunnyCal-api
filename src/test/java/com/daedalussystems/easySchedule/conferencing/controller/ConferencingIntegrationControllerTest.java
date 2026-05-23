package com.daedalussystems.easySchedule.conferencing.controller;

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

import com.daedalussystems.easySchedule.calendar.auth.OAuthStateException;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingOAuthService;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingOAuthServiceRegistry;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingProviderCapabilities;
import com.daedalussystems.easySchedule.conferencing.service.GoogleMeetConferencingOAuthService;
import com.daedalussystems.easySchedule.conferencing.service.ZoomConferencingOAuthService;
import com.daedalussystems.easySchedule.integration.ProviderAuthoritySummary;
import com.daedalussystems.easySchedule.integration.ProviderCatalogResponse;
import com.daedalussystems.easySchedule.integration.ProviderCatalogService;
import com.daedalussystems.easySchedule.integration.ProviderCapabilityRegistry;
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
                "http://localhost:3000/error",
                "http://localhost:3000/success");
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
        when(zoomOAuthService.status(userId)).thenReturn("CONNECTED");
        when(zoomOAuthService.capabilities()).thenReturn(ConferencingProviderCapabilities.standalone());
        when(googleMeetOAuthService.status(userId)).thenReturn("CONNECTED");
        when(googleMeetOAuthService.capabilities())
                .thenReturn(ConferencingProviderCapabilities.managedBy("google_calendar"));
        ProviderCatalogResponse response = new ProviderCatalogResponse(
                "v1alpha-provider-catalog",
                List.of(),
                new ProviderAuthoritySummary("google", List.of("google"), "google", List.of("zoom")));
        when(providerCatalogService.catalogForUser(userId)).thenReturn(response);
        when(providerCatalogService.conferencingProviderSubset(userId)).thenReturn(Map.of());

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
    }

    @Test
    void status_reflectsDisconnectedManagedProvider() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null);
        when(zoomOAuthService.status(userId)).thenReturn("NOT_CONNECTED");
        when(zoomOAuthService.capabilities()).thenReturn(ConferencingProviderCapabilities.standalone());
        when(googleMeetOAuthService.status(userId)).thenReturn("NOT_CONNECTED");
        when(googleMeetOAuthService.capabilities())
                .thenReturn(ConferencingProviderCapabilities.managedBy("google_calendar"));
        when(providerCatalogService.catalogForUser(userId)).thenReturn(new ProviderCatalogResponse(
                "v1alpha-provider-catalog", List.of(),
                new ProviderAuthoritySummary("google", List.of(), "google", List.of())));
        when(providerCatalogService.conferencingProviderSubset(userId)).thenReturn(Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> providers = (Map<String, Map<String, Object>>) controller.status(authentication)
                .getBody().getData().get("providers");

        assertEquals(Boolean.FALSE, providers.get("zoom").get("connected"));
        assertEquals(Boolean.FALSE, providers.get("google_meet").get("connected"));
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
