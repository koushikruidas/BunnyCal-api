package io.bunnycal.calendar.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.bunnycal.calendar.config.GoogleOAuthProperties;
import io.bunnycal.calendar.config.MicrosoftOAuthProperties;
import io.bunnycal.calendar.auth.OAuthStateException;
import io.bunnycal.calendar.replay.WebhookDeliveryMetadata;
import io.bunnycal.calendar.service.CalendarConnectionManagementService;
import io.bunnycal.calendar.service.CalendarWebhookAuthService;
import io.bunnycal.calendar.service.CalendarOAuthService;
import io.bunnycal.calendar.service.MicrosoftCalendarOAuthService;
import io.bunnycal.calendar.service.CalendarWebhookIngestionService;
import io.bunnycal.calendar.dto.GoogleWebhookRequest;
import io.bunnycal.calendar.dto.CalendarRuntimeStatusResponse;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.integration.ProviderCapabilityRegistry;
import io.bunnycal.integration.ProviderAuthoritySummary;
import io.bunnycal.integration.ProviderCatalogResponse;
import io.bunnycal.integration.ProviderCatalogService;
import io.bunnycal.calendar.service.CalendarRuntimeStatusService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CalendarIntegrationControllerTest {
    @Mock
    private CalendarOAuthService oauthService;
    @Mock
    private MicrosoftCalendarOAuthService microsoftOAuthService;
    @Mock
    private CalendarWebhookIngestionService webhookIngestionService;
    @Mock
    private CalendarWebhookAuthService webhookAuthService;
    @Mock
    private ProviderCatalogService providerCatalogService;
    @Mock
    private CalendarRuntimeStatusService calendarRuntimeStatusService;
    @Mock
    private CalendarConnectionManagementService connectionManagementService;

    private CalendarIntegrationController controller;
    private GoogleOAuthProperties properties;
    private MicrosoftOAuthProperties microsoftProperties;
    private ProviderCapabilityRegistry capabilityRegistry;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new GoogleOAuthProperties();
        microsoftProperties = new MicrosoftOAuthProperties();
        capabilityRegistry = new ProviderCapabilityRegistry();
        properties.setFrontendSuccessRedirect("http://localhost:3000/success");
        properties.setFrontendErrorRedirect("http://localhost:3000/error");
        microsoftProperties.setFrontendSuccessRedirect("http://localhost:3000/success");
        microsoftProperties.setFrontendErrorRedirect("http://localhost:3000/error");
        meterRegistry = new SimpleMeterRegistry();
        controller = new CalendarIntegrationController(
                oauthService,
                microsoftOAuthService,
                webhookAuthService,
                webhookIngestionService,
                properties,
                microsoftProperties,
                capabilityRegistry,
                providerCatalogService,
                calendarRuntimeStatusService,
                connectionManagementService,
                meterRegistry,
                "secret");
    }

    @Test
    void connectReturnsRedirectUrl() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(oauthService.buildGoogleConnectUrl(userId, null, null, null)).thenReturn("https://accounts.google.com/...");

        ApiResponse<Map<String, String>> body = controller.connectGoogle(auth, null, null, null).getBody();

        assertEquals(true, body.isSuccess());
        assertEquals("https://accounts.google.com/...", body.getData().get("redirectUrl"));
    }

    @Test
    void callbackSuccessRedirects() {
        when(oauthService.handleGoogleCallback("code", "state"))
                .thenReturn(new CalendarOAuthService.OAuthCallbackResult("dashboard", null, null));
        var response = controller.callbackGoogle("code", "state");
        assertEquals(302, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getLocation());
        assertEquals("http://localhost:3000/dashboard/integrations?integrationSuccess=google",
                response.getHeaders().getLocation().toString());
        verify(oauthService).handleGoogleCallback("code", "state");
    }

    @Test
    void callbackSuccessRedirectsToStateReturnToWithQueryAndHash() {
        when(oauthService.handleGoogleCallback("code", "state"))
                .thenReturn(new CalendarOAuthService.OAuthCallbackResult(
                        "dashboard",
                        "/dashboard/integrations?tab=calendar#providers",
                        null));
        var response = controller.callbackGoogle("code", "state");
        assertEquals(302, response.getStatusCode().value());
        assertEquals("http://localhost:3000/dashboard/integrations?tab=calendar&integrationSuccess=google#providers",
                response.getHeaders().getLocation().toString());
    }

    @Test
    void callbackSuccessPublicFallbackRedirectsToRoot() {
        when(oauthService.handleGoogleCallback("code", "state"))
                .thenReturn(new CalendarOAuthService.OAuthCallbackResult("public-booking", null, null));
        var response = controller.callbackGoogle("code", "state");
        assertEquals(302, response.getStatusCode().value());
        assertEquals("http://localhost:3000/?integrationSuccess=google",
                response.getHeaders().getLocation().toString());
    }

    @Test
    void statusReturnsCanonicalRuntimeShape() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(calendarRuntimeStatusService.runtimeStatus(userId)).thenReturn(
                new CalendarRuntimeStatusResponse(
                        "application",
                        new CalendarRuntimeStatusResponse.Identity("google", "host@example.com"),
                        List.of(),
                        new CalendarRuntimeStatusResponse.Conferencing(true, true, false, "google_meet")
                ));

        ApiResponse<CalendarRuntimeStatusResponse> body = controller.status(auth).getBody();

        assertEquals(true, body.isSuccess());
        assertEquals("application", body.getData().lifecycleAuthority());
        assertEquals("google", body.getData().identity().provider());
        assertEquals("host@example.com", body.getData().identity().email());
        assertEquals(true, body.getData().conferencing().zoomConnected());
    }

    @Test
    void disconnectGoogleCallsService() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        ApiResponse<Void> body = controller.disconnect("google", auth).getBody();
        assertEquals(true, body.isSuccess());
        verify(oauthService).disconnectGoogle(userId);
    }

    @Test
    void callbackFailureRedirectsWithErrorCode() {
        when(oauthService.handleGoogleCallback("code", "state")).thenThrow(new IllegalArgumentException("bad"));
        var response = controller.callbackGoogle("code", "state");
        assertEquals(302, response.getStatusCode().value());
        assertEquals("http://localhost:3000/error?error=oauth_invalid_response&code=oauth_invalid_response",
                response.getHeaders().getLocation().toString());
    }

    @Test
    void callbackFailureRedirectsWithStateExpiredCode() {
        when(oauthService.handleGoogleCallback("code", "state"))
                .thenThrow(new OAuthStateException(OAuthStateException.Reason.EXPIRED, "expired"));
        var response = controller.callbackGoogle("code", "state");
        assertEquals(302, response.getStatusCode().value());
        assertEquals("http://localhost:3000/error?error=oauth_state_expired&code=oauth_state_expired",
                response.getHeaders().getLocation().toString());
    }

    @Test
    void webhook_ingestAccepted_whenSecretValid() {
        GoogleWebhookRequest request = new GoogleWebhookRequest(UUID.randomUUID(), "evt-1", "{\"id\":\"evt-1\"}");
        ApiResponse<Void> body = controller.ingestGoogleWebhook("secret", request).getBody();
        assertEquals(true, body.isSuccess());
        verify(webhookIngestionService).ingestGoogle(
                request.connectionId(), request.providerEventId(), request.rawPayload(), WebhookDeliveryMetadata.empty());
        verify(webhookAuthService).verifyGoogle("secret", "secret", null, null, request);
    }

    @Test
    void webhook_rejects_whenSecretInvalid() {
        GoogleWebhookRequest request = new GoogleWebhookRequest(UUID.randomUUID(), "evt-1", "{}");
        doThrow(new CustomException(ErrorCode.UNAUTHORIZED, "Invalid webhook secret."))
                .when(webhookAuthService).verifyGoogle(any(), any(), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertThrows(CustomException.class,
                () -> controller.ingestGoogleWebhook("wrong", request));
    }

    @Test
    void webhook_googlePushHeaders_resolvesConnectionByChannel() {
        UUID connectionId = UUID.randomUUID();
        when(oauthService.findGoogleConnectionIdByWebhookChannel("ch-1")).thenReturn(Optional.of(connectionId));

        ApiResponse<Void> body = controller.ingestGoogleWebhook(
                null, null, null, null,
                "ch-1", "secret", "77", "res-1", "exists",
                null, null, null, null).getBody();

        assertEquals(true, body.isSuccess());
        verify(webhookAuthService).verifyGoogleWatchNotification("secret", "secret");
        verify(webhookIngestionService).ingestGoogle(
                eq(connectionId),
                eq("google_channel_signal"),
                any(),
                any(WebhookDeliveryMetadata.class));
    }

    @Test
    void providerAwareStatus_includesAuthorityAndCatalogBlocks() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(oauthService.googleConnectionStatus(userId)).thenReturn("CONNECTED");
        when(microsoftOAuthService.microsoftConnectionStatus(userId)).thenReturn("NOT_CONNECTED");
        ProviderCatalogResponse catalogResponse = new ProviderCatalogResponse(
                "v1alpha-provider-catalog",
                List.of(),
                new ProviderAuthoritySummary("google", List.of("google"), "application", List.of("zoom")));
        when(providerCatalogService.catalogForUser(userId)).thenReturn(catalogResponse);
        when(providerCatalogService.calendarProviderSubset(userId)).thenReturn(Map.of());

        ApiResponse<Map<String, Object>> body = controller.providerAwareStatus(auth).getBody();

        assertEquals(true, body.isSuccess());
        assertNotNull(body.getData().get("providerCatalog"));
        assertNotNull(body.getData().get("authority"));
    }

    /**
     * Graph sends the subscription handshake as POST ?validationToken=… with a text/plain body.
     * Must run through MockMvc: the regression was in request mapping / content negotiation, which
     * a direct controller-method call bypasses entirely.
     */
    @Test
    void microsoftWebhook_postValidationHandshake_echoesTokenAsPlainText() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/integrations/calendar/webhooks/microsoft")
                        .param("validationToken", "tok-123")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(""))
                .andExpect(status().isOk())
                .andExpect(content().string("tok-123"));
    }

    @Test
    void microsoftWebhook_getValidationHandshake_echoesToken() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/integrations/calendar/webhooks/microsoft")
                        .param("validationToken", "tok-456"))
                .andExpect(status().isOk())
                .andExpect(content().string("tok-456"));
    }

    @Test
    void providerAwareStatus_setsDeprecationHeaders() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        ProviderCatalogResponse catalogResponse = new ProviderCatalogResponse(
                "v1alpha-provider-catalog",
                List.of(),
                new ProviderAuthoritySummary("google", List.of("google"), "application", List.of("zoom")));
        when(oauthService.googleConnectionStatus(userId)).thenReturn("CONNECTED");
        when(microsoftOAuthService.microsoftConnectionStatus(userId)).thenReturn("NOT_CONNECTED");
        when(providerCatalogService.catalogForUser(userId)).thenReturn(catalogResponse);
        when(providerCatalogService.calendarProviderSubset(userId)).thenReturn(Map.of());

        var response = controller.providerAwareStatus(auth);

        assertEquals("true", response.getHeaders().getFirst("Deprecation"));
        assertNotNull(response.getHeaders().getFirst("Sunset"));
        assertNotNull(response.getHeaders().getFirst("Warning"));
    }
}
