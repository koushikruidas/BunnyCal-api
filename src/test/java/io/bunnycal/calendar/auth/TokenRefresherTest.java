package io.bunnycal.calendar.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.eq;

import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.TokenRefreshResult;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.service.CalendarConnectionWriteService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenRefresherTest {

    @Mock
    private CalendarConnectionRepository repository;
    @Mock
    private TokenCipher tokenCipher;
    @Mock
    private CalendarTokenClient googleTokenClient;
    @Mock
    private CalendarConnectionWriteService connectionWriteService;
    @Mock
    private AccessTokenCache accessTokenCache;

    private TokenRefresher tokenRefresher;

    @BeforeEach
    void setUp() {
        lenient().when(googleTokenClient.provider()).thenReturn(CalendarProviderType.GOOGLE);
        CalendarTokenClientRegistry registry = new CalendarTokenClientRegistry(List.of(googleTokenClient));
        tokenRefresher = new TokenRefresher(repository, tokenCipher, registry, connectionWriteService, accessTokenCache, new SimpleMeterRegistry());
        lenient().when(connectionWriteService.markActive(any(), any(), any(), any())).thenAnswer(inv -> new CalendarConnection());
        lenient().when(connectionWriteService.markFailure(any(), any(), any(), any(), any())).thenAnswer(inv -> new CalendarConnection());
        lenient().when(accessTokenCache.get(any())).thenReturn(Optional.empty());
    }

    @Test
    void expiredToken_refreshesBeforeCall() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenReturn(new TokenRefreshResult("new-token", Instant.now().plusSeconds(3600)));

        String result = tokenRefresher.executeWithValidToken(id, token -> "ok:" + token);

        assertEquals("ok:new-token", result);
        verify(connectionWriteService).markActive(any(), any(), any(), any());
    }

    @Test
    void nonExpiredToken_usesCachedTokenWithoutRefreshingAgain() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(3600), CalendarConnectionStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenReturn(new TokenRefreshResult("new-token", Instant.now().plusSeconds(3600)));
        when(accessTokenCache.get(id))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new AccessTokenCache.CachedToken("new-token", Instant.now().plusSeconds(3600))));

        String first = tokenRefresher.executeWithValidToken(id, token -> "ok:" + token);
        String second = tokenRefresher.executeWithValidToken(id, token -> "ok2:" + token);

        assertEquals("ok:new-token", first);
        assertEquals("ok2:new-token", second);
        verify(googleTokenClient, times(1)).refreshAccessToken("refresh");
        verify(connectionWriteService, times(1)).markActive(any(), any(), any(), any());
        verify(accessTokenCache, times(1)).put(any(), any(), any());
        verify(googleTokenClient, times(1)).refreshAccessToken(any());
    }

    @Test
    void unauthorized_refreshesAndRetriesOnce() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(3600), CalendarConnectionStatus.ACTIVE);

        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenReturn(new TokenRefreshResult("new-token", Instant.now().plusSeconds(3600)));

        final int[] attempts = {0};
        String result = tokenRefresher.executeWithValidToken(id, token -> {
            attempts[0]++;
            if (attempts[0] == 1) {
                throw new CalendarClientException(401, "unauthorized");
            }
            return token;
        });

        assertEquals("new-token", result);
        verify(connectionWriteService, times(2)).markActive(any(), any(), any(), any());
    }

    @Test
    void refreshFailure_marksRevoked() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);

        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenThrow(new RuntimeException("refresh failed"));

        assertThrows(RuntimeException.class,
                () -> tokenRefresher.executeWithValidToken(id, token -> "never"));

        verify(connectionWriteService).markFailure(eq(id), eq(CalendarConnectionStatus.ERROR), any(), any(), any());
    }

    @Test
    void revokedConnection_rejectedWithoutCall() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(3600), CalendarConnectionStatus.REVOKED);
        when(repository.findById(id)).thenReturn(Optional.of(conn));

        assertThrows(IllegalStateException.class,
                () -> tokenRefresher.executeWithValidToken(id, token -> token));

        verify(googleTokenClient, never()).refreshAccessToken(any());
    }

    @Test
    void errorConnection_withRecentFailure_skipsRefreshDuringCooldown() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(3600), CalendarConnectionStatus.ERROR);
        conn.setLastErrorAt(Instant.now().minusSeconds(10));
        when(repository.findById(id)).thenReturn(Optional.of(conn));

        assertThrows(IllegalStateException.class,
                () -> tokenRefresher.executeWithValidToken(id, token -> token));

        verify(googleTokenClient, never()).refreshAccessToken(any());
    }

    private static CalendarConnection connection(UUID id, Instant expiresAt, CalendarConnectionStatus status) {
        CalendarConnection conn = new CalendarConnection();
        conn.setProvider(CalendarProviderType.GOOGLE);
        conn.setLastTokenExpiresAt(expiresAt);
        conn.setStatus(status);
        conn.setRefreshTokenCiphertext("cipher");
        try {
            var idField = CalendarConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(conn, id);
        } catch (Exception ignored) {
        }
        return conn;
    }
}
