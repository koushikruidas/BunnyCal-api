package io.bunnycal.calendar.service;

import io.bunnycal.calendar.client.OAuthErrorCategory;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
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

    // F7 backoff schedule (capped at TIER_MAX). Index = failureCount-1.
    private static final Duration[] BACKOFF_TIERS = new Duration[] {
            Duration.ofMinutes(1),   // 1st failure
            Duration.ofMinutes(5),   // 2nd
            Duration.ofMinutes(15),  // 3rd
            Duration.ofHours(1),     // 4th
            Duration.ofHours(6),     // 5th
            Duration.ofHours(24)     // 6th and beyond (cap)
    };

    // F8 thresholds for transient overflow escalating into REVOKED quarantine.
    // 12 transient failures across the backoff schedule above is roughly 1.5 days of fruitless
    // retries — enough signal that this isn't a flaky provider blip.
    static final int TRANSIENT_QUARANTINE_THRESHOLD = 12;
    static final Duration QUARANTINE_DURATION = Duration.ofDays(7);

    private final CalendarConnectionRepository repository;
    private final MeterRegistry meterRegistry;
    @PersistenceContext
    private EntityManager entityManager;

    public CalendarConnectionWriteService(CalendarConnectionRepository repository,
                                          MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
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
        return markActiveInternal(connectionId, expiresAt, lastSyncedAt, null, context);
    }

    /**
     * F4: variant that also persists a rotated refresh-token ciphertext atomically with the
     * success transition. The caller is responsible for encryption.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CalendarConnection markActiveWithRotatedToken(UUID connectionId,
                                                         Instant expiresAt,
                                                         Instant lastSyncedAt,
                                                         String rotatedRefreshTokenCiphertext,
                                                         String context) {
        return markActiveInternal(connectionId, expiresAt, lastSyncedAt, rotatedRefreshTokenCiphertext, context);
    }

    private CalendarConnection markActiveInternal(UUID connectionId,
                                                  Instant expiresAt,
                                                  Instant lastSyncedAt,
                                                  String rotatedRefreshTokenCiphertext,
                                                  String context) {
        return withRetry(connectionId, context, latest -> {
            latest.setStatus(CalendarConnectionStatus.ACTIVE);
            latest.setLastErrorCode(null);
            latest.setLastErrorAt(null);
            // F7: success clears all retry state.
            latest.setFailureCount(0);
            latest.setNextRetryAt(null);
            latest.setQuarantinedUntil(null);
            if (expiresAt != null) {
                latest.setLastTokenExpiresAt(expiresAt);
            }
            if (shouldAdvanceLastSyncedAt(latest.getLastSyncedAt(), lastSyncedAt)) {
                latest.setLastSyncedAt(lastSyncedAt);
            }
            if (rotatedRefreshTokenCiphertext != null && !rotatedRefreshTokenCiphertext.isBlank()) {
                latest.setRefreshTokenCiphertext(rotatedRefreshTokenCiphertext);
            }
            return latest;
        });
    }

    /**
     * Back-compat: stamp status + error code + last_error_at; also auto-manages retry state
     * based on the requested status:
     *   REVOKED → clear next_retry_at (terminal), failure_count preserved for observability.
     *   FAILED/ERROR → increment failure_count, schedule next_retry_at via backoff schedule.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CalendarConnection markFailure(UUID connectionId,
                                          CalendarConnectionStatus status,
                                          String errorCode,
                                          Instant errorAt,
                                          String context) {
        return markFailureInternal(connectionId, status, null, errorCode, errorAt, context);
    }

    /**
     * F6: category-aware variant. TokenRefresher and the scheduler should prefer this.
     * Drives REVOKED vs ERROR vs FAILED, and triggers F8 quarantine when transient failures
     * exceed the threshold.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CalendarConnection markFailureWithCategory(UUID connectionId,
                                                      OAuthErrorCategory category,
                                                      String errorCode,
                                                      Instant errorAt,
                                                      String context) {
        CalendarConnectionStatus status = statusForCategory(category);
        return markFailureInternal(connectionId, status, category, errorCode, errorAt, context);
    }

    private CalendarConnection markFailureInternal(UUID connectionId,
                                                   CalendarConnectionStatus requestedStatus,
                                                   OAuthErrorCategory category,
                                                   String errorCode,
                                                   Instant errorAt,
                                                   String context) {
        return withRetry(connectionId, context, latest -> {
            int newFailureCount = latest.getFailureCount() + 1;
            CalendarConnectionStatus finalStatus = requestedStatus;
            Instant nextRetryAt = null;
            Instant quarantinedUntil = latest.getQuarantinedUntil();

            if (requestedStatus == CalendarConnectionStatus.REVOKED) {
                // Terminal — explicit user disconnect or invalid_grant. No retry scheduled.
                nextRetryAt = null;
                quarantinedUntil = null;
            } else if (requestedStatus == CalendarConnectionStatus.FAILED
                    || requestedStatus == CalendarConnectionStatus.ERROR) {
                // F8: transient overflow → escalate to REVOKED quarantine.
                if (category == OAuthErrorCategory.TRANSIENT
                        && newFailureCount >= TRANSIENT_QUARANTINE_THRESHOLD) {
                    finalStatus = CalendarConnectionStatus.REVOKED;
                    quarantinedUntil = (errorAt == null ? Instant.now() : errorAt).plus(QUARANTINE_DURATION);
                    nextRetryAt = null;
                    if (meterRegistry != null) {
                        meterRegistry.counter("calendar.connection.quarantined.total",
                                        "provider", providerTag(latest),
                                        "reason", "transient_overflow")
                                .increment();
                        meterRegistry.counter("calendar.retry.exhausted.total",
                                        "provider", providerTag(latest))
                                .increment();
                    }
                    log.warn("calendar_connection_quarantined connectionId={} userId={} provider={} failureCount={} reason=transient_overflow quarantinedUntil={}",
                            latest.getId(), latest.getUserId(), latest.getProvider(),
                            newFailureCount, quarantinedUntil);
                } else {
                    nextRetryAt = (errorAt == null ? Instant.now() : errorAt).plus(backoffFor(newFailureCount));
                    if (meterRegistry != null) {
                        meterRegistry.counter("calendar.retry.backoff_scheduled.total",
                                        "provider", providerTag(latest),
                                        "status", finalStatus.name())
                                .increment();
                    }
                }
            }

            latest.setStatus(finalStatus);
            latest.setLastErrorCode(errorCode);
            latest.setLastErrorAt(errorAt);
            latest.setFailureCount(newFailureCount);
            latest.setNextRetryAt(nextRetryAt);
            latest.setQuarantinedUntil(quarantinedUntil);

            if (meterRegistry != null && category != null) {
                String metric = category == OAuthErrorCategory.TERMINAL
                        ? "calendar.oauth.terminal_failure.total"
                        : category == OAuthErrorCategory.TRANSIENT
                                ? "calendar.oauth.transient_failure.total"
                                : "calendar.oauth.unknown_failure.total";
                meterRegistry.counter(metric,
                                "provider", providerTag(latest),
                                "errorCode", errorCode == null ? "unknown" : errorCode)
                        .increment();
            }

            log.info("calendar_connection_mark_failure connectionId={} userId={} provider={} requestedStatus={} finalStatus={} category={} errorCode={} failureCount={} nextRetryAt={} quarantinedUntil={} context={}",
                    latest.getId(), latest.getUserId(), latest.getProvider(),
                    requestedStatus, finalStatus, category, errorCode,
                    newFailureCount, nextRetryAt, quarantinedUntil, context);
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
    public CalendarConnection updateWebhookChannel(UUID connectionId,
                                                   String channelId,
                                                   String resourceId,
                                                   Instant channelExpiresAt,
                                                   String context) {
        return withRetry(connectionId, context, latest -> {
            latest.setWebhookChannelId(channelId);
            latest.setWebhookResourceId(resourceId);
            latest.setWebhookChannelExpiresAt(channelExpiresAt);
            return latest;
        });
    }

    /**
     * F9: clear the encrypted refresh token after a successful disconnect/revoke. Leaves
     * the row in place for audit but ensures no revoked secret remains on disk.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CalendarConnection clearRefreshTokenCiphertext(UUID connectionId, String context) {
        return withRetry(connectionId, context, latest -> {
            latest.setRefreshTokenCiphertext("");
            latest.setWebhookChannelId(null);
            latest.setWebhookResourceId(null);
            latest.setWebhookChannelExpiresAt(null);
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
        target.setFailureCount(source.getFailureCount());
        target.setNextRetryAt(source.getNextRetryAt());
        target.setQuarantinedUntil(source.getQuarantinedUntil());
    }

    private static boolean equalsNullable(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private static boolean shouldAdvanceLastSyncedAt(Instant current, Instant candidate) {
        if (candidate == null) {
            return false;
        }
        return current == null || candidate.isAfter(current);
    }

    private static CalendarConnectionStatus statusForCategory(OAuthErrorCategory category) {
        if (category == null) {
            return CalendarConnectionStatus.FAILED;
        }
        return switch (category) {
            case TERMINAL -> CalendarConnectionStatus.REVOKED;
            case TRANSIENT -> CalendarConnectionStatus.ERROR;
            case UNKNOWN -> CalendarConnectionStatus.FAILED;
        };
    }

    static Duration backoffFor(int failureCount) {
        if (failureCount <= 0) {
            return BACKOFF_TIERS[0];
        }
        int idx = Math.min(failureCount - 1, BACKOFF_TIERS.length - 1);
        return BACKOFF_TIERS[idx];
    }

    private static String providerTag(CalendarConnection connection) {
        return connection.getProvider() == null
                ? "unknown"
                : connection.getProvider().name().toLowerCase(java.util.Locale.ROOT);
    }
}
