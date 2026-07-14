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
import io.bunnycal.calendar.client.OAuthError;
import io.bunnycal.calendar.client.OAuthErrorCategory;
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
        lenient().when(connectionWriteService.markActiveWithRotatedToken(any(), any(), any(), any(), any())).thenAnswer(inv -> new CalendarConnection());
        lenient().when(connectionWriteService.markFailure(any(), any(), any(), any(), any())).thenAnswer(inv -> new CalendarConnection());
        lenient().when(connectionWriteService.markFailureWithCategory(any(), any(), any(), any(), any())).thenAnswer(inv -> new CalendarConnection());
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
    void refreshFailure_genericRuntimeException_marksTransient() {
        // A bare RuntimeException with no typed OAuth payload is treated as transient (ERROR),
        // not terminal — the network/upstream might recover.
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);

        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenThrow(new RuntimeException("refresh failed"));

        assertThrows(RuntimeException.class,
                () -> tokenRefresher.executeWithValidToken(id, token -> "never"));

        verify(connectionWriteService).markFailureWithCategory(
                eq(id), eq(OAuthErrorCategory.TRANSIENT), any(), any(), any());
    }

    @Test
    void refreshFailure_invalidGrant_marksTerminal() {
        // F6: a typed OAuthError with TERMINAL category triggers REVOKED.
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);
        OAuthError terminal = new OAuthError("invalid_grant", "expired", 400,
                CalendarProviderType.GOOGLE, OAuthErrorCategory.TERMINAL);

        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenThrow(new CalendarClientException(400, "body", terminal));

        assertThrows(CalendarClientException.class,
                () -> tokenRefresher.executeWithValidToken(id, token -> "never"));

        verify(connectionWriteService).markFailureWithCategory(
                eq(id), eq(OAuthErrorCategory.TERMINAL), eq("invalid_grant"), any(), any());
    }

    @Test
    void refreshSuccess_withRotatedToken_persistsViaMarkActiveWithRotatedToken() {
        // F4: provider returned a new refresh_token different from the stored one;
        // TokenRefresher must encrypt and persist atomically with the active-token update.
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("old-refresh");
        when(tokenCipher.encrypt("new-refresh")).thenReturn("new-cipher");
        when(googleTokenClient.refreshAccessToken("old-refresh"))
                .thenReturn(new TokenRefreshResult("access-1", Instant.now().plusSeconds(3600), "new-refresh"));

        tokenRefresher.executeWithValidToken(id, token -> token);

        verify(connectionWriteService).markActiveWithRotatedToken(
                eq(id), any(), any(), eq("new-cipher"), any());
        verify(connectionWriteService, never()).markActive(eq(id), any(), any(), any());
    }

    @Test
    void refreshSuccess_withoutRotation_persistsViaPlainMarkActive() {
        // F4: provider did NOT rotate (Google's common case); use the original markActive
        // signature so the ciphertext column is untouched.
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenReturn(new TokenRefreshResult("access-1", Instant.now().plusSeconds(3600), null));

        tokenRefresher.executeWithValidToken(id, token -> token);

        verify(connectionWriteService).markActive(eq(id), any(), any(), any());
        verify(connectionWriteService, never())
                .markActiveWithRotatedToken(eq(id), any(), any(), any(), any());
    }

    @Test
    void refreshSuccess_withSameRefreshTokenEcho_doesNotRotate() {
        // Defensive: some providers echo the same refresh_token. Don't treat that as a
        // rotation event.
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("same-refresh");
        when(googleTokenClient.refreshAccessToken("same-refresh"))
                .thenReturn(new TokenRefreshResult("access-1", Instant.now().plusSeconds(3600), "same-refresh"));

        tokenRefresher.executeWithValidToken(id, token -> token);

        verify(connectionWriteService).markActive(eq(id), any(), any(), any());
        verify(connectionWriteService, never())
                .markActiveWithRotatedToken(eq(id), any(), any(), any(), any());
    }

    @Test
    void revokedConnection_rejectedWithoutCall() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(3600), CalendarConnectionStatus.REVOKED);
        when(repository.findById(id)).thenReturn(Optional.of(conn));

        CalendarConnectionStateException ex = assertThrows(CalendarConnectionStateException.class,
                () -> tokenRefresher.executeWithValidToken(id, token -> token));

        assertEquals("CONNECTION_REVOKED", ex.getErrorCode());
        verify(googleTokenClient, never()).refreshAccessToken(any());
    }

    @Test
    void errorConnection_withRecentFailure_skipsRefreshDuringCooldown() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(3600), CalendarConnectionStatus.ERROR);
        conn.setLastErrorAt(Instant.now().minusSeconds(10));
        when(repository.findById(id)).thenReturn(Optional.of(conn));

        CalendarConnectionStateException ex = assertThrows(CalendarConnectionStateException.class,
                () -> tokenRefresher.executeWithValidToken(id, token -> token));

        assertEquals("PROVIDER_DOWN", ex.getErrorCode());
        verify(googleTokenClient, never()).refreshAccessToken(any());
    }

    // ── Credential identity drift ────────────────────────────────────────────
    // A connection whose stored refresh token belongs to a different account than the row claims
    // keeps working today: it silently reads and writes that other account's calendars. Every
    // provider call funnels through the refresher, so refusing the token here is what stops a
    // drifted connection from being used at all.

    @Test
    void refresh_whenTokenBelongsToAnotherAccount_marksTerminalAndNeverRunsOperation() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);
        conn.setProviderUserId("111852416355544746941");
        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenReturn(new TokenRefreshResult("new-token", Instant.now().plusSeconds(3600)));
        // The token answers for a different Google account than the row is keyed on.
        when(googleTokenClient.fetchProviderUserId("new-token")).thenReturn("999999999999999999999");

        CalendarConnectionStateException ex = assertThrows(CalendarConnectionStateException.class,
                () -> tokenRefresher.executeWithValidToken(id, token -> "must-not-run"));

        assertEquals("CONNECTION_IDENTITY_DRIFT", ex.getErrorCode());
        assertEquals(false, ex.isRetryable());
        verify(connectionWriteService).markFailureWithCategory(
                eq(id), eq(OAuthErrorCategory.TERMINAL), eq("CONNECTION_IDENTITY_DRIFT"), any(), any());
        // The drifted token must never be persisted, cached, or handed to the caller.
        verify(connectionWriteService, never()).markActive(any(), any(), any(), any());
        verify(accessTokenCache, never()).put(any(), any(), any());
    }

    @Test
    void refresh_whenTokenMatchesConnection_proceedsNormally() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);
        conn.setProviderUserId("111852416355544746941");
        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenReturn(new TokenRefreshResult("new-token", Instant.now().plusSeconds(3600)));
        when(googleTokenClient.fetchProviderUserId("new-token")).thenReturn("111852416355544746941");

        String result = tokenRefresher.executeWithValidToken(id, token -> "ok:" + token);

        assertEquals("ok:new-token", result);
        verify(connectionWriteService).markActive(any(), any(), any(), any());
        verify(connectionWriteService, never()).markFailureWithCategory(any(), any(), any(), any(), any());
    }

    // Fail closed only on a CONFIRMED mismatch — a probe that errors or answers blank leaves
    // identity unverified. Revoking on an unverified answer would let a provider outage take out
    // every connection at once.

    @Test
    void refresh_whenIdentityProbeFails_leavesConnectionUsable() {
        UUID id = UUID.randomUUID();
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);
        conn.setProviderUserId("111852416355544746941");
        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenReturn(new TokenRefreshResult("new-token", Instant.now().plusSeconds(3600)));
        when(googleTokenClient.fetchProviderUserId("new-token"))
                .thenThrow(new RuntimeException("userinfo endpoint 503"));

        String result = tokenRefresher.executeWithValidToken(id, token -> "ok:" + token);

        assertEquals("ok:new-token", result);
        verify(connectionWriteService, never()).markFailureWithCategory(any(), any(), any(), any(), any());
    }

    @Test
    void refresh_whenConnectionHasNoProviderUserId_skipsTheAssertion() {
        UUID id = UUID.randomUUID();
        // providerUserId left null — nothing to compare against, so the guard must not fire.
        CalendarConnection conn = connection(id, Instant.now().plusSeconds(60), CalendarConnectionStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(conn));
        when(tokenCipher.decrypt("cipher")).thenReturn("refresh");
        when(googleTokenClient.refreshAccessToken("refresh"))
                .thenReturn(new TokenRefreshResult("new-token", Instant.now().plusSeconds(3600)));

        String result = tokenRefresher.executeWithValidToken(id, token -> "ok:" + token);

        assertEquals("ok:new-token", result);
        verify(googleTokenClient, never()).fetchProviderUserId(any());
        verify(connectionWriteService, never()).markFailureWithCategory(any(), any(), any(), any(), any());
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
