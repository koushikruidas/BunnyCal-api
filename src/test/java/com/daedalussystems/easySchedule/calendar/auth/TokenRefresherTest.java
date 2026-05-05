package com.daedalussystems.easySchedule.calendar.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import com.daedalussystems.easySchedule.calendar.client.TokenRefreshResult;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import java.time.Instant;
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
    private GoogleApiClient googleApiClient;

    private TokenRefresher tokenRefresher;

    @BeforeEach
    void setUp() {
        tokenRefresher = new TokenRefresher(repository, tokenCipher, googleApiClient, new SimpleMeterRegistry());
    }

    @Test
    void expiredToken_refreshesBeforeCall() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleApiClient.refreshAccessToken("refresh"))
                .thenReturn(new TokenRefreshResult("new-token", Instant.now().plusSeconds(3600)));

        String result = tokenRefresher.executeWithValidToken(id, token -> "ok:" + token);

        assertEquals("ok:new-token", result);
        verify(repository).saveAndFlush(conn);
    }

    @Test
    void unauthorized_refreshesAndRetriesOnce() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(3600), CalendarConnectionStatus.ACTIVE);

        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleApiClient.refreshAccessToken("refresh"))
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
        verify(repository, times(2)).saveAndFlush(conn);
    }

    @Test
    void refreshFailure_marksRevoked() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);

        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleApiClient.refreshAccessToken("refresh"))
                .thenThrow(new RuntimeException("refresh failed"));

        assertThrows(RuntimeException.class,
                () -> tokenRefresher.executeWithValidToken(id, token -> "never"));

        assertEquals(CalendarConnectionStatus.ERROR, conn.getStatus());
        verify(repository).saveAndFlush(conn);
    }

    @Test
    void revokedConnection_rejectedWithoutCall() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(3600), CalendarConnectionStatus.REVOKED);
        when(repository.findById(id)).thenReturn(Optional.of(conn));

        assertThrows(IllegalStateException.class,
                () -> tokenRefresher.executeWithValidToken(id, token -> token));

        verify(googleApiClient, never()).refreshAccessToken(any());
    }

    private static CalendarConnection connection(UUID id, Instant expiresAt, CalendarConnectionStatus status) {
        CalendarConnection conn = new CalendarConnection();
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
