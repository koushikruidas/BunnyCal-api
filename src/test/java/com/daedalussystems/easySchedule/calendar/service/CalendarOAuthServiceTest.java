package com.daedalussystems.easySchedule.calendar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.auth.OAuthStateService;
import com.daedalussystems.easySchedule.calendar.auth.TokenCipher;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import com.daedalussystems.easySchedule.calendar.client.OAuthTokenExchangeResult;
import com.daedalussystems.easySchedule.calendar.config.CalendarSecurityProperties;
import com.daedalussystems.easySchedule.calendar.config.GoogleOAuthProperties;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class CalendarOAuthServiceTest {
    @Mock
    private CalendarConnectionRepository repository;
    @Mock
    private GoogleApiClient googleApiClient;
    @Mock
    private TokenCipher tokenCipher;

    private CalendarOAuthService service;
    private OAuthStateService stateService;
    private GoogleOAuthProperties properties;
    private CalendarSecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new GoogleOAuthProperties();
        properties.setClientId("cid");
        properties.setClientSecret("csecret");
        properties.setRedirectUri("http://localhost/callback");
        securityProperties = new CalendarSecurityProperties();
        securityProperties.setOauthStateSecret("state-secret");
        stateService = new OAuthStateService(securityProperties);
        service = new CalendarOAuthService(repository, googleApiClient, properties, stateService, tokenCipher);
    }

    @Test
    void buildGoogleConnectUrl_containsExpectedOAuthParams() {
        UUID userId = UUID.randomUUID();

        String url = service.buildGoogleConnectUrl(userId);
        assertNotNull(url);
        assertEquals(true, url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));

        String query = url.substring(url.indexOf('?') + 1);
        Map<String, String> params = parseQuery(query);
        assertEquals("cid", params.get("client_id"));
        assertEquals("http://localhost/callback", params.get("redirect_uri"));
        assertEquals("code", params.get("response_type"));
        assertEquals("offline", params.get("access_type"));
        assertNotNull(params.get("state"));
    }

    @Test
    void callbackSuccess_persistsEncryptedTokens() {
        UUID userId = UUID.randomUUID();
        String state = stateService.generate(userId);
        when(googleApiClient.exchangeCodeForToken("code", "http://localhost/callback", "cid", "csecret"))
                .thenReturn(new OAuthTokenExchangeResult("access-1", "refresh-1", Instant.now().plusSeconds(3600)));
        when(googleApiClient.fetchProviderUserId("access-1")).thenReturn("sub-1");
        when(repository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE)).thenReturn(Optional.empty());
        when(tokenCipher.encrypt("refresh-1")).thenReturn("enc-refresh");

        service.handleGoogleCallback("code", state);

        ArgumentCaptor<CalendarConnection> captor = ArgumentCaptor.forClass(CalendarConnection.class);
        verify(repository).save(captor.capture());
        CalendarConnection saved = captor.getValue();
        assertEquals(userId, saved.getUserId());
        assertEquals(CalendarProviderType.GOOGLE, saved.getProvider());
        assertEquals("enc-refresh", saved.getRefreshTokenCiphertext());
        assertEquals(List.of(
                "https://www.googleapis.com/auth/calendar.events",
                "https://www.googleapis.com/auth/calendar.readonly"), saved.getScopes());
        assertEquals(CalendarConnectionStatus.ACTIVE, saved.getStatus());
        assertNull(saved.getLastErrorCode());
        assertNull(saved.getLastErrorAt());
    }

    @Test
    void callbackInvalidState_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.handleGoogleCallback("code", "bad-state"));
    }

    @Test
    void callbackWithoutRefreshToken_reusesExistingCiphertext() {
        UUID userId = UUID.randomUUID();
        String state = stateService.generate(userId);
        CalendarConnection existing = new CalendarConnection();
        existing.setRefreshTokenCiphertext("existing-cipher");
        when(repository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE)).thenReturn(Optional.of(existing));
        when(googleApiClient.exchangeCodeForToken("code", "http://localhost/callback", "cid", "csecret"))
                .thenReturn(new OAuthTokenExchangeResult("access-2", null, Instant.now().plusSeconds(1800)));
        when(googleApiClient.fetchProviderUserId("access-2")).thenReturn("sub-2");

        service.handleGoogleCallback("code", state);

        ArgumentCaptor<CalendarConnection> captor = ArgumentCaptor.forClass(CalendarConnection.class);
        verify(repository).save(captor.capture());
        assertEquals("existing-cipher", captor.getValue().getRefreshTokenCiphertext());
    }

    @Test
    void statusMapsConnectionState() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE)).thenReturn(Optional.empty());
        assertEquals("NOT_CONNECTED", service.googleConnectionStatus(userId));

        CalendarConnection conn = new CalendarConnection();
        conn.setStatus(CalendarConnectionStatus.ACTIVE);
        when(repository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE)).thenReturn(Optional.of(conn));
        assertEquals("CONNECTED", service.googleConnectionStatus(userId));
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
