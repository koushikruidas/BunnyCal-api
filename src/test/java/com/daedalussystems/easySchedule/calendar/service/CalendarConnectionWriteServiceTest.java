package com.daedalussystems.easySchedule.calendar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
        service = new CalendarConnectionWriteService(repository);
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
    void saveSnapshot_withNullId_createsWithoutFindById() {
        CalendarConnection candidate = new CalendarConnection();
        candidate.setUserId(UUID.randomUUID());
        candidate.setProvider(com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType.GOOGLE);
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
        c.setProvider(com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType.GOOGLE);
        try {
            var idField = CalendarConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
        } catch (Exception ignored) {
        }
        return c;
    }
}
