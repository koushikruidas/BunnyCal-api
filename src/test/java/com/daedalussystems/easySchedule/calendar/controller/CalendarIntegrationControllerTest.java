package com.daedalussystems.easySchedule.calendar.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.config.GoogleOAuthProperties;
import com.daedalussystems.easySchedule.calendar.config.MicrosoftOAuthProperties;
import com.daedalussystems.easySchedule.calendar.auth.OAuthStateException;
import com.daedalussystems.easySchedule.calendar.replay.WebhookDeliveryMetadata;
import com.daedalussystems.easySchedule.calendar.service.CalendarWebhookAuthService;
import com.daedalussystems.easySchedule.calendar.service.CalendarOAuthService;
import com.daedalussystems.easySchedule.calendar.service.MicrosoftCalendarOAuthService;
import com.daedalussystems.easySchedule.calendar.service.CalendarWebhookIngestionService;
import com.daedalussystems.easySchedule.calendar.dto.GoogleWebhookRequest;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.integration.ProviderCapabilityRegistry;
import com.daedalussystems.easySchedule.integration.ProviderAuthoritySummary;
import com.daedalussystems.easySchedule.integration.ProviderCatalogResponse;
import com.daedalussystems.easySchedule.integration.ProviderCatalogService;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

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

    private CalendarIntegrationController controller;
    private GoogleOAuthProperties properties;
    private MicrosoftOAuthProperties microsoftProperties;
    private ProviderCapabilityRegistry capabilityRegistry;

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
        controller = new CalendarIntegrationController(oauthService, microsoftOAuthService, webhookAuthService, webhookIngestionService, properties, microsoftProperties, capabilityRegistry, providerCatalogService, "secret");
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
    void statusReturnsMappedValue() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(oauthService.googleConnectionStatus(userId)).thenReturn("CONNECTED");
        when(microsoftOAuthService.microsoftConnectionStatus(userId)).thenReturn("NOT_CONNECTED");

        ApiResponse<Map<String, String>> body = controller.status(auth).getBody();

        assertEquals(true, body.isSuccess());
        assertEquals("CONNECTED", body.getData().get("google"));
        assertEquals("NOT_CONNECTED", body.getData().get("microsoft"));
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
                new ProviderAuthoritySummary("google", List.of("google"), "google", List.of("zoom")));
        when(providerCatalogService.catalogForUser(userId)).thenReturn(catalogResponse);
        when(providerCatalogService.calendarProviderSubset(userId)).thenReturn(Map.of());

        ApiResponse<Map<String, Object>> body = controller.providerAwareStatus(auth).getBody();

        assertEquals(true, body.isSuccess());
        assertNotNull(body.getData().get("providerCatalog"));
        assertNotNull(body.getData().get("authority"));
    }
}
