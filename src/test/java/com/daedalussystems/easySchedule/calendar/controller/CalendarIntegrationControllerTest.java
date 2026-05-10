package com.daedalussystems.easySchedule.calendar.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.config.GoogleOAuthProperties;
import com.daedalussystems.easySchedule.calendar.service.CalendarOAuthService;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import java.util.Map;
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

    private CalendarIntegrationController controller;
    private GoogleOAuthProperties properties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new GoogleOAuthProperties();
        properties.setFrontendSuccessRedirect("http://localhost:3000/success");
        properties.setFrontendErrorRedirect("http://localhost:3000/error");
        controller = new CalendarIntegrationController(oauthService, properties);
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
        assertEquals("http://localhost:3000/success", response.getHeaders().getLocation().toString());
        verify(oauthService).handleGoogleCallback("code", "state");
    }

    @Test
    void statusReturnsMappedValue() {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        when(oauthService.googleConnectionStatus(userId)).thenReturn("CONNECTED");

        ApiResponse<Map<String, String>> body = controller.status(auth).getBody();

        assertEquals(true, body.isSuccess());
        assertEquals("CONNECTED", body.getData().get("google"));
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
        assertEquals("http://localhost:3000/error?code=OAUTH_INVALID_RESPONSE",
                response.getHeaders().getLocation().toString());
    }
}
