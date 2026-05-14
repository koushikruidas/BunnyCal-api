package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarConnectionWriteService {
    private static final Logger log = LoggerFactory.getLogger(CalendarConnectionWriteService.class);
    private static final int MAX_RETRIES = 4;

    private final CalendarConnectionRepository repository;
    @PersistenceContext
    private EntityManager entityManager;

    public CalendarConnectionWriteService(CalendarConnectionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CalendarConnection saveSnapshot(CalendarConnection candidate, String context) {
        if (candidate == null) {
            throw new IllegalArgumentException("Calendar connection snapshot candidate is required");
        }
        log.info("calendar_connection_save_snapshot context={} candidateId={} userId={} provider={} status={}",
                context, candidate.getId(), candidate.getUserId(), candidate.getProvider(), candidate.getStatus());
        if (candidate.getId() == null) {
            return repository.saveAndFlush(candidate);
        }
        return withRetry(candidate.getId(), context, latest -> {
            copySnapshot(candidate, latest);
            return latest;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CalendarConnection markActive(UUID connectionId, Instant expiresAt, Instant lastSyncedAt, String context) {
        return withRetry(connectionId, context, latest -> {
            latest.setStatus(CalendarConnectionStatus.ACTIVE);
            latest.setLastErrorCode(null);
            latest.setLastErrorAt(null);
            if (expiresAt != null) {
                latest.setLastTokenExpiresAt(expiresAt);
            }
            if (lastSyncedAt != null) {
                latest.setLastSyncedAt(lastSyncedAt);
            }
            return latest;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CalendarConnection markFailure(UUID connectionId,
                                          CalendarConnectionStatus status,
                                          String errorCode,
                                          Instant errorAt,
                                          String context) {
        return withRetry(connectionId, context, latest -> {
            latest.setStatus(status);
            latest.setLastErrorCode(errorCode);
            latest.setLastErrorAt(errorAt);
            return latest;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CalendarConnection updateLastSyncedAt(UUID connectionId, Instant lastSyncedAt, String context) {
        return withRetry(connectionId, context, latest -> {
            latest.setLastSyncedAt(lastSyncedAt);
            return latest;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean advanceProviderCursor(UUID connectionId,
                                         String expectedCursor,
                                         String nextCursor,
                                         Instant observedAt,
                                         String context) {
        if (nextCursor == null || nextCursor.isBlank()) {
            return false;
        }
        return withRetry(connectionId, context, latest -> {
            String current = latest.getProviderSyncCursor();
            if (!equalsNullable(current, expectedCursor)) {
                return null;
            }
            latest.setProviderSyncCursor(nextCursor);
            latest.setProviderCursorUpdatedAt(observedAt == null ? Instant.now() : observedAt);
            latest.setProviderCursorInvalidatedAt(null);
            return latest;
        }) != null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CalendarConnection invalidateProviderCursor(UUID connectionId, Instant invalidatedAt, String context) {
        return withRetry(connectionId, context, latest -> {
            latest.setProviderSyncCursor(null);
            latest.setProviderCursorInvalidatedAt(invalidatedAt == null ? Instant.now() : invalidatedAt);
            return latest;
        });
    }

    private interface Mutator {
        CalendarConnection apply(CalendarConnection latest);
    }

    private CalendarConnection withRetry(UUID connectionId, String context, Mutator mutator) {
        if (connectionId == null) {
            throw new IllegalArgumentException("Calendar connection id is required for update path");
        }
        OptimisticLockingFailureException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("calendar_connection_lookup context={} connectionId={} attempt={}",
                        context, connectionId, attempt);
                CalendarConnection latest = repository.findById(connectionId)
                        .orElseThrow(() -> new IllegalArgumentException("Calendar connection not found"));
                CalendarConnection toSave = mutator.apply(latest);
                if (toSave == null) {
                    return null;
                }
                return repository.saveAndFlush(toSave);
            } catch (OptimisticLockingFailureException conflict) {
                lastConflict = conflict;
                log.warn("calendar_connection_write_conflict connectionId={} context={} attempt={} maxAttempts={}",
                        connectionId, context, attempt, MAX_RETRIES);
                if (entityManager != null) {
                    entityManager.clear();
                }
            }
        }
        throw lastConflict;
    }

    private static void copySnapshot(CalendarConnection source, CalendarConnection target) {
        target.setUserId(source.getUserId());
        target.setProvider(source.getProvider());
        target.setProviderUserId(source.getProviderUserId());
        target.setRefreshTokenCiphertext(source.getRefreshTokenCiphertext());
        target.setLastTokenExpiresAt(source.getLastTokenExpiresAt());
        List<String> scopes = source.getScopes();
        target.setScopes(scopes == null ? List.of() : List.copyOf(scopes));
        target.setStatus(source.getStatus());
        target.setLastErrorCode(source.getLastErrorCode());
        target.setLastErrorAt(source.getLastErrorAt());
        target.setLastSyncedAt(source.getLastSyncedAt());
        target.setProviderSyncCursor(source.getProviderSyncCursor());
        target.setProviderCursorUpdatedAt(source.getProviderCursorUpdatedAt());
        target.setProviderCursorInvalidatedAt(source.getProviderCursorInvalidatedAt());
        target.setWebhookChannelId(source.getWebhookChannelId());
        target.setWebhookResourceId(source.getWebhookResourceId());
        target.setWebhookChannelExpiresAt(source.getWebhookChannelExpiresAt());
    }

    private static boolean equalsNullable(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
}
