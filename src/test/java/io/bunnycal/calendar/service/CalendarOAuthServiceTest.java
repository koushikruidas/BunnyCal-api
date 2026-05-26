package io.bunnycal.calendar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;

import io.bunnycal.calendar.auth.OAuthStateService;
import io.bunnycal.calendar.auth.OAuthStateException;
import io.bunnycal.calendar.auth.TokenCipher;
import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.client.GoogleApiClient;
import io.bunnycal.calendar.client.OAuthTokenExchangeResult;
import io.bunnycal.calendar.config.CalendarSecurityProperties;
import io.bunnycal.calendar.config.GoogleOAuthProperties;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.dao.OptimisticLockingFailureException;

class CalendarOAuthServiceTest {
    @Mock
    private CalendarConnectionRepository repository;
    @Mock
    private GoogleApiClient googleApiClient;
    @Mock
    private TokenCipher tokenCipher;
    @Mock
    private CalendarEventIngestionService ingestionService;
    @Mock
    private ExternalCalendarSyncClient syncClient;
    @Mock
    private SlotCacheVersionService slotCacheVersionService;
    @Mock
    private CalendarConnectionWriteService connectionWriteService;

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
        properties.setScopes(List.of(
                "https://www.googleapis.com/auth/calendar.events",
                "https://www.googleapis.com/auth/calendar.readonly"));
        securityProperties = new CalendarSecurityProperties();
        securityProperties.setOauthStateSecret("state-secret");
        stateService = new OAuthStateService(securityProperties, new ObjectMapper());
        when(syncClient.provider()).thenReturn(CalendarProviderType.GOOGLE);
        CalendarSyncClientRegistry syncClientRegistry = new CalendarSyncClientRegistry(List.of(syncClient));
        service = new CalendarOAuthService(
                repository, googleApiClient, properties, stateService, tokenCipher, ingestionService, syncClientRegistry, slotCacheVersionService, connectionWriteService,
                new SimpleMeterRegistry(),
                "http://localhost:8080/integrations/calendar/webhooks/google", "secret");
        when(repository.save(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(connectionWriteService.saveSnapshot(any(CalendarConnection.class), any())).thenAnswer(inv -> inv.getArgument(0));
        when(connectionWriteService.markFailure(any(), any(), any(), any(), any())).thenAnswer(inv -> new CalendarConnection());
        when(syncClient.fetchFull(any(CalendarConnection.class), any()))
                .thenReturn(new ExternalCalendarSyncClient.SyncBatch(List.of(), null, true, false, "test"));
        when(googleApiClient.watchEvents(any(), any(), any()))
                .thenReturn(new GoogleApiClient.WatchChannel("ch-1", "res-1", Instant.now().plusSeconds(3600)));
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
        verify(connectionWriteService, atLeastOnce()).saveSnapshot(captor.capture(), any());
        CalendarConnection saved = captor.getAllValues().get(captor.getAllValues().size() - 1);
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
        assertThrows(OAuthStateException.class, () -> service.handleGoogleCallback("code", "bad-state"));
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
        verify(connectionWriteService, atLeastOnce()).saveSnapshot(captor.capture(), any());
        CalendarConnection saved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("existing-cipher", saved.getRefreshTokenCiphertext());
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

        conn.setStatus(CalendarConnectionStatus.REVOKED);
        assertEquals("DISCONNECTED", service.googleConnectionStatus(userId));

        conn.setStatus(CalendarConnectionStatus.DISCONNECTED);
        assertEquals("DISCONNECTED", service.googleConnectionStatus(userId));

        conn.setStatus(CalendarConnectionStatus.ERROR);
        assertEquals("ERROR", service.googleConnectionStatus(userId));
    }

    @Test
    void callback_whenWriterFails_propagatesException() {
        UUID userId = UUID.randomUUID();
        String state = stateService.generate(userId);
        CalendarConnection existing = new CalendarConnection();
        existing.setUserId(userId);
        existing.setProvider(CalendarProviderType.GOOGLE);
        existing.setRefreshTokenCiphertext("existing-cipher");

        when(repository.findByUserIdAndProvider(userId, CalendarProviderType.GOOGLE))
                .thenReturn(Optional.of(existing));
        when(googleApiClient.exchangeCodeForToken("code", "http://localhost/callback", "cid", "csecret"))
                .thenReturn(new OAuthTokenExchangeResult("access-2", "refresh-2", Instant.now().plusSeconds(1800)));
        when(googleApiClient.fetchProviderUserId("access-2")).thenReturn("sub-2");
        when(tokenCipher.encrypt("refresh-2")).thenReturn("enc-refresh-2");
        when(connectionWriteService.saveSnapshot(any(CalendarConnection.class), any()))
                .thenThrow(new OptimisticLockingFailureException("conflict"))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThrows(OptimisticLockingFailureException.class, () -> service.handleGoogleCallback("code", state));
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
