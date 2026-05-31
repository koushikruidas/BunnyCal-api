package io.bunnycal.calendar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import io.bunnycal.calendar.client.OAuthErrorCategory;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class CalendarConnectionWriteServiceTest {

    @Mock
    private CalendarConnectionRepository repository;

    private CalendarConnectionWriteService service;

    @BeforeEach
    void setUp() {
        service = new CalendarConnectionWriteService(repository, new SimpleMeterRegistry());
    }

    @Test
    void markFailure_retriesOnOptimisticConflict() {
        UUID id = UUID.randomUUID();
        CalendarConnection latest = connection(id);
        when(repository.findById(id)).thenReturn(Optional.of(latest), Optional.of(latest));
        when(repository.saveAndFlush(any(CalendarConnection.class)))
                .thenThrow(new OptimisticLockingFailureException("conflict"))
                .thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.markFailure(id, CalendarConnectionStatus.FAILED, "SYNC_FAILED", Instant.now(), "test");

        assertEquals(CalendarConnectionStatus.FAILED, saved.getStatus());
        assertEquals("SYNC_FAILED", saved.getLastErrorCode());
        verify(repository, times(2)).saveAndFlush(any(CalendarConnection.class));
    }

    @Test
    void markActive_setsActiveAndClearsError() {
        UUID id = UUID.randomUUID();
        CalendarConnection latest = connection(id);
        latest.setStatus(CalendarConnectionStatus.FAILED);
        latest.setLastErrorCode("OLD");
        latest.setLastErrorAt(Instant.now().minusSeconds(30));
        Instant expiry = Instant.now().plusSeconds(3600);
        Instant syncedAt = Instant.now();
        when(repository.findById(id)).thenReturn(Optional.of(latest));
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.markActive(id, expiry, syncedAt, "test");

        assertEquals(CalendarConnectionStatus.ACTIVE, saved.getStatus());
        assertEquals(null, saved.getLastErrorCode());
        assertEquals(null, saved.getLastErrorAt());
        assertEquals(expiry, saved.getLastTokenExpiresAt());
        assertEquals(syncedAt, saved.getLastSyncedAt());
    }

    @Test
    void markActive_doesNotMoveLastSyncedAtBackwards() {
        UUID id = UUID.randomUUID();
        CalendarConnection latest = connection(id);
        Instant current = Instant.parse("2026-05-18T08:40:00Z");
        Instant staleCandidate = Instant.parse("2026-05-18T08:35:00Z");
        latest.setLastSyncedAt(current);
        when(repository.findById(id)).thenReturn(Optional.of(latest));
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.markActive(id, null, staleCandidate, "test");

        assertEquals(current, saved.getLastSyncedAt());
    }

    @Test
    void markFailure_failedStatus_incrementsCountAndSchedulesNextRetry() {
        // F7: a transient/unknown failure schedules next_retry_at and increments failure_count.
        UUID id = UUID.randomUUID();
        CalendarConnection latest = connection(id);
        latest.setStatus(CalendarConnectionStatus.ACTIVE);
        latest.setFailureCount(0);
        Instant errorAt = Instant.parse("2026-05-26T10:00:00Z");
        when(repository.findById(id)).thenReturn(Optional.of(latest));
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.markFailure(id, CalendarConnectionStatus.FAILED, "SYNC_FAILED", errorAt, "test");

        assertEquals(CalendarConnectionStatus.FAILED, saved.getStatus());
        assertEquals(1, saved.getFailureCount());
        assertNotNull(saved.getNextRetryAt());
        // 1st failure → 1 min cooldown.
        assertEquals(errorAt.plusSeconds(60), saved.getNextRetryAt());
    }

    @Test
    void markFailure_revokedStatus_clearsNextRetry() {
        // Terminal: no retry scheduled. failure_count preserved for observability.
        UUID id = UUID.randomUUID();
        CalendarConnection latest = connection(id);
        latest.setFailureCount(3);
        latest.setNextRetryAt(Instant.now().plusSeconds(60));
        when(repository.findById(id)).thenReturn(Optional.of(latest));
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.markFailure(id, CalendarConnectionStatus.REVOKED, "invalid_grant", Instant.now(), "test");

        assertEquals(CalendarConnectionStatus.REVOKED, saved.getStatus());
        assertNull(saved.getNextRetryAt());
        assertNull(saved.getQuarantinedUntil());
    }

    @Test
    void markFailureWithCategory_transientOverflow_quarantines() {
        // F8: TRANSIENT category at/past threshold escalates to REVOKED + quarantined_until.
        UUID id = UUID.randomUUID();
        CalendarConnection latest = connection(id);
        latest.setFailureCount(CalendarConnectionWriteService.TRANSIENT_QUARANTINE_THRESHOLD - 1);
        when(repository.findById(id)).thenReturn(Optional.of(latest));
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.markFailureWithCategory(
                id, OAuthErrorCategory.TRANSIENT, "http_503", Instant.now(), "test");

        assertEquals(CalendarConnectionStatus.REVOKED, saved.getStatus());
        assertNotNull(saved.getQuarantinedUntil());
        assertNull(saved.getNextRetryAt());
    }

    @Test
    void markFailureWithCategory_terminalCategory_marksRevokedWithoutQuarantine() {
        UUID id = UUID.randomUUID();
        CalendarConnection latest = connection(id);
        when(repository.findById(id)).thenReturn(Optional.of(latest));
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.markFailureWithCategory(
                id, OAuthErrorCategory.TERMINAL, "invalid_grant", Instant.now(), "test");

        assertEquals(CalendarConnectionStatus.REVOKED, saved.getStatus());
        assertEquals("invalid_grant", saved.getLastErrorCode());
        assertNull(saved.getNextRetryAt());
        assertNull(saved.getQuarantinedUntil()); // explicit invalid_grant is terminal, not quarantine.
    }

    @Test
    void markActive_clearsAllRetryState() {
        UUID id = UUID.randomUUID();
        CalendarConnection latest = connection(id);
        latest.setStatus(CalendarConnectionStatus.FAILED);
        latest.setFailureCount(5);
        latest.setNextRetryAt(Instant.now().plusSeconds(900));
        latest.setQuarantinedUntil(Instant.now().plusSeconds(3600));
        latest.setLastErrorCode("invalid_grant");
        latest.setLastErrorAt(Instant.now());
        when(repository.findById(id)).thenReturn(Optional.of(latest));
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.markActive(id, Instant.now().plusSeconds(3600), Instant.now(), "test");

        assertEquals(CalendarConnectionStatus.ACTIVE, saved.getStatus());
        assertEquals(0, saved.getFailureCount());
        assertNull(saved.getNextRetryAt());
        assertNull(saved.getQuarantinedUntil());
        assertNull(saved.getLastErrorCode());
        assertNull(saved.getLastErrorAt());
    }

    @Test
    void markActiveWithRotatedToken_updatesCiphertext() {
        // F4: rotated refresh-token ciphertext is persisted atomically with the active state.
        UUID id = UUID.randomUUID();
        CalendarConnection latest = connection(id);
        latest.setRefreshTokenCiphertext("old-cipher");
        when(repository.findById(id)).thenReturn(Optional.of(latest));
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.markActiveWithRotatedToken(
                id, Instant.now().plusSeconds(3600), Instant.now(), "new-cipher", "test");

        assertEquals("new-cipher", saved.getRefreshTokenCiphertext());
        assertEquals(CalendarConnectionStatus.ACTIVE, saved.getStatus());
    }

    @Test
    void clearRefreshTokenCiphertext_emptiesCiphertextAndWebhook() {
        // F9: clear refresh token + webhook handles after disconnect.
        UUID id = UUID.randomUUID();
        CalendarConnection latest = connection(id);
        latest.setRefreshTokenCiphertext("secret");
        latest.setWebhookChannelId("ch-123");
        latest.setWebhookResourceId("res-456");
        latest.setWebhookChannelExpiresAt(Instant.now().plusSeconds(3600));
        when(repository.findById(id)).thenReturn(Optional.of(latest));
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.clearRefreshTokenCiphertext(id, "test");

        assertTrue(saved.getRefreshTokenCiphertext().isEmpty());
        assertNull(saved.getWebhookChannelId());
        assertNull(saved.getWebhookResourceId());
        assertNull(saved.getWebhookChannelExpiresAt());
    }

    @Test
    void saveSnapshot_withNullId_createsWithoutFindById() {
        CalendarConnection candidate = new CalendarConnection();
        candidate.setUserId(UUID.randomUUID());
        candidate.setProvider(CalendarProviderType.GOOGLE);
        candidate.setProviderUserId("provider-user");
        candidate.setRefreshTokenCiphertext("cipher");
        candidate.setLastTokenExpiresAt(Instant.now().plusSeconds(100));
        candidate.setStatus(CalendarConnectionStatus.SYNCING);
        when(repository.saveAndFlush(any(CalendarConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        CalendarConnection saved = service.saveSnapshot(candidate, "test-create");

        assertEquals(candidate.getUserId(), saved.getUserId());
        verify(repository, never()).findById(any());
        verify(repository, times(1)).saveAndFlush(any(CalendarConnection.class));
    }

    private static CalendarConnection connection(UUID id) {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(UUID.randomUUID());
        c.setProvider(CalendarProviderType.GOOGLE);
        try {
            var idField = CalendarConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
        } catch (Exception ignored) {
        }
        return c;
    }
}
