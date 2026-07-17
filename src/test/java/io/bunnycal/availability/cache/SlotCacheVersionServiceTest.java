package io.bunnycal.availability.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SlotCacheVersionServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SlotCacheVersionService versionService;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        versionService = new SlotCacheVersionService(redisTemplate);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
        mocks.close();
    }

    @Test
    void getCurrentVersion_returnsSentinelOnRedisFailure() {
        UUID userId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), eq(java.util.List.of("slots:version:" + userId))))
                .thenThrow(new RuntimeException("redis down"));

        long version = versionService.getCurrentVersion(userId);

        assertEquals(SlotCacheVersionService.VERSION_UNAVAILABLE, version);
    }

    @Test
    void getCurrentVersion_returnsSentinelOnCorruptValue() {
        UUID userId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), eq(java.util.List.of("slots:version:" + userId))))
                .thenReturn(null);

        long version = versionService.getCurrentVersion(userId);

        assertEquals(SlotCacheVersionService.VERSION_UNAVAILABLE, version);
    }

    @Test
    void getCurrentVersion_atomicallyPersistsTheInitialVersion() {
        UUID userId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), eq(java.util.List.of("slots:version:" + userId))))
                .thenReturn(1L);

        assertEquals(1L, versionService.getCurrentVersion(userId));
    }

    @Test
    void firstBumpOnAnAbsentKey_advancesPastTheInitialVersion() {
        UUID userId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), eq(java.util.List.of("slots:version:" + userId))))
                .thenReturn(2L);

        assertEquals(2L, versionService.incrementVersion(userId));
    }

    @Test
    void bumpVersionAfterCommit_runsImmediatelyWhenNoTransactionActive() {
        UUID userId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), eq(java.util.List.of("slots:version:" + userId))))
                .thenReturn(2L);

        versionService.bumpVersionAfterCommit(userId);

        verify(redisTemplate, times(1)).execute(any(RedisScript.class), eq(java.util.List.of("slots:version:" + userId)));
    }

    @Test
    void bumpVersionAfterCommit_deferredUntilAfterCommit() {
        UUID userId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), eq(java.util.List.of("slots:version:" + userId))))
                .thenReturn(3L);

        TransactionSynchronizationManager.initSynchronization();
        try {
            versionService.bumpVersionAfterCommit(userId);

            // Synchronization should be registered, but the bump must not have run yet.
            verify(redisTemplate, never()).execute(any(RedisScript.class), eq(java.util.List.of("slots:version:" + userId)));
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());

            // Simulate Spring's commit phase firing the synchronization callbacks.
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(redisTemplate, times(1)).execute(any(RedisScript.class), eq(java.util.List.of("slots:version:" + userId)));
    }

    @Test
    void bumpVersionAfterCommit_doesNotRunIfTransactionDoesNotCommit() {
        UUID userId = UUID.randomUUID();

        TransactionSynchronizationManager.initSynchronization();
        try {
            versionService.bumpVersionAfterCommit(userId);

            // Simulate rollback: callbacks are registered but afterCommit is never invoked.
            assertNotNull(TransactionSynchronizationManager.getSynchronizations());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(redisTemplate, never()).execute(any(RedisScript.class), eq(java.util.List.of("slots:version:" + userId)));
    }

    @Test
    void bumpVersionAfterCommit_ignoresNullUserId() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            versionService.bumpVersionAfterCommit(null);
            assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(redisTemplate, never()).execute(any(RedisScript.class), any());
    }
}
