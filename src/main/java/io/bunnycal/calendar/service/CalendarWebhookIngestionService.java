package io.bunnycal.calendar.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.OAuthError;
import io.bunnycal.calendar.client.OAuthErrorCategory;
import io.bunnycal.calendar.config.CalendarWebhookProperties;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.replay.WebhookDeliveryMetadata;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarWebhookIngestionService {
    private static final Logger log = LoggerFactory.getLogger(CalendarWebhookIngestionService.class);

    private final CalendarWebhookDedupService dedupService;
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarSyncClientRegistry syncClientRegistry;
    private final CalendarEventIngestionService ingestionService;
    private final CalendarWebhookReplayCaptureService replayCaptureService;
    private final CalendarConnectionWriteService connectionWriteService;
    private final SlotCacheVersionService slotCacheVersionService;
    private final MeterRegistry meterRegistry;
    private final CalendarWebhookProperties webhookProperties;
    private final Counter webhookIngestTotal;
    private final Counter webhookDuplicateTotal;
    private final Counter reconciliationConflictTotal;

    public CalendarWebhookIngestionService(
            CalendarWebhookDedupService dedupService,
            CalendarConnectionRepository connectionRepository,
            CalendarSyncClientRegistry syncClientRegistry,
            CalendarEventIngestionService ingestionService,
            CalendarWebhookReplayCaptureService replayCaptureService,
            CalendarConnectionWriteService connectionWriteService,
            SlotCacheVersionService slotCacheVersionService,
            MeterRegistry meterRegistry,
            CalendarWebhookProperties webhookProperties) {
        this.dedupService = dedupService;
        this.connectionRepository = connectionRepository;
        this.syncClientRegistry = syncClientRegistry;
        this.ingestionService = ingestionService;
        this.replayCaptureService = replayCaptureService;
        this.connectionWriteService = connectionWriteService;
        this.slotCacheVersionService = slotCacheVersionService;
        this.meterRegistry = meterRegistry;
        this.webhookProperties = webhookProperties;
        this.webhookIngestTotal = Counter.builder("webhook_ingest_total").register(meterRegistry);
        this.webhookDuplicateTotal = Counter.builder("webhook_duplicate_total").register(meterRegistry);
        this.reconciliationConflictTotal = Counter.builder("reconciliation_conflict_total").register(meterRegistry);
    }

    @Transactional
    public void ingestGoogle(UUID connectionId, String providerEventId, String rawPayload) {
        ingestGoogle(connectionId, providerEventId, rawPayload, WebhookDeliveryMetadata.empty());
    }

    @Transactional
    public void ingestMicrosoft(UUID connectionId, String providerEventId, String rawPayload) {
        ingestMicrosoft(connectionId, providerEventId, rawPayload, WebhookDeliveryMetadata.empty());
    }

    @Transactional
    public void ingestMicrosoft(UUID connectionId, String providerEventId, String rawPayload, WebhookDeliveryMetadata deliveryMetadata) {
        ingest("microsoft", CalendarProviderType.MICROSOFT, connectionId, providerEventId, rawPayload, deliveryMetadata);
    }

    @Transactional
    public void ingestGoogle(UUID connectionId, String providerEventId, String rawPayload, WebhookDeliveryMetadata deliveryMetadata) {
        ingest("google", CalendarProviderType.GOOGLE, connectionId, providerEventId, rawPayload, deliveryMetadata);
    }

    private void ingest(String provider,
                        CalendarProviderType providerType,
                        UUID connectionId,
                        String providerEventId,
                        String rawPayload,
                        WebhookDeliveryMetadata deliveryMetadata) {
        if (!webhookProperties.isProviderWebhookEnabled(providerType)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "Calendar webhook ingestion is disabled.");
        }
        if (connectionId == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "connectionId is required.");
        }
        String normalizedProviderEventId = (providerEventId == null || providerEventId.isBlank())
                ? provider + "_channel_signal"
                : providerEventId;
        putIfAbsent("correlationId", normalizedProviderEventId);
        putIfAbsent("causationId", normalizedProviderEventId);
        putIfAbsent("bookingId", "");
        putIfAbsent("externalEventId", normalizedProviderEventId);
        putIfAbsent("syncSource", SyncSourceAttribution.WEBHOOK.name());
        try {
            webhookIngestTotal.increment();
            CalendarWebhookDedupService.DedupOutcome dedupOutcome =
                    dedupService.checkAndRecord(provider, connectionId, normalizedProviderEventId, rawPayload);
            log.info("webhook_dedup_outcome provider={} connectionId={} providerEventId={} firstSeen={} deliveryKey={} payloadHash={}",
                    provider,
                    connectionId, normalizedProviderEventId, dedupOutcome.firstSeen(), dedupOutcome.deliveryKey(), dedupOutcome.payloadHash());
            replayCaptureService.capture(
                    provider,
                    connectionId,
                    normalizedProviderEventId,
                    rawPayload,
                    dedupOutcome.deliveryKey(),
                    dedupOutcome.payloadHash(),
                    !dedupOutcome.firstSeen(),
                    deliveryMetadata);
            if (!dedupOutcome.firstSeen()) {
                webhookDuplicateTotal.increment();
                reconciliationConflictTotal.increment();
                log.info("calendar_webhook_duplicate provider={} providerEventId={} connectionId={}",
                        provider, normalizedProviderEventId, connectionId);
                return;
            }

            CalendarConnection connection = connectionRepository.findById(connectionId)
                    .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Calendar connection not found."));
            CalendarConnectionStatus prevStatus = connection.getStatus();
            String expectedCursor = connection.getProviderSyncCursor();
            ExternalCalendarSyncClient syncClient = syncClientRegistry.clientFor(connection);
            try {
                ExternalCalendarSyncClient.SyncBatch batch =
                        syncClient.fetchIncremental(connection, SyncSourceAttribution.WEBHOOK);
                log.info("incremental_fetch_result provider={} source=WEBHOOK mode=incremental recoveryAction={} gapSuspected={} eventsCount={} nextCursorPresent={}",
                        provider,
                        batch.recoveryAction(), batch.gapSuspected(), batch.events().size(), batch.nextCursor() != null);
                if (batch.gapSuspected()) {
                    meterRegistry.counter("calendar.sync.webhook_gap_suspected.total", "provider", provider, "action", batch.recoveryAction())
                            .increment();
                }
                ingestionService.upsertEvents(connectionId, batch.events(), SyncSourceAttribution.WEBHOOK);
                if (batch.events().isEmpty()) {
                    meterRegistry.counter("calendar.sync.provider_drift_detected.total", "provider", provider, "source", "WEBHOOK")
                            .increment();
                }
                if (batch.nextCursor() != null) {
                    boolean advanced = connectionWriteService.advanceProviderCursor(
                            connection.getId(), expectedCursor, batch.nextCursor(), Instant.now(), "webhook_incremental_cursor_advance");
                    log.info("cursor_advance_attempt provider={} source=WEBHOOK connectionId={} advanced={} expectedCursorPresent={} nextCursorPresent={}",
                            provider,
                            connection.getId(), advanced, expectedCursor != null && !expectedCursor.isBlank(), batch.nextCursor() != null);
                    if (!advanced) {
                        meterRegistry.counter("calendar.sync.cursor_conflict.total", "provider", provider, "source", "WEBHOOK")
                                .increment();
                    }
                }
                if (prevStatus != CalendarConnectionStatus.ACTIVE) {
                    slotCacheVersionService.bumpVersion(connection.getUserId());
                }
                connectionWriteService.markActive(
                        connection.getId(),
                        connection.getLastTokenExpiresAt(),
                        connection.getLastSyncedAt(),
                        "webhook_incremental_success");
                log.info("calendar_webhook_ingested provider={} providerEventId={} connectionId={} mode=incremental",
                        provider, normalizedProviderEventId, connectionId);
            } catch (ExternalCalendarSyncClient.SyncTokenInvalidException invalid) {
                connectionWriteService.invalidateProviderCursor(connection.getId(), Instant.now(), "webhook_sync_cursor_invalidated");
                ExternalCalendarSyncClient.SyncBatch fullBatch =
                        syncClient.fetchFull(connection, SyncSourceAttribution.WEBHOOK);
                log.info("incremental_fetch_result provider={} source=WEBHOOK mode=full_recovery recoveryAction={} gapSuspected={} eventsCount={} nextCursorPresent={}",
                        provider,
                        fullBatch.recoveryAction(), fullBatch.gapSuspected(), fullBatch.events().size(), fullBatch.nextCursor() != null);
                ingestionService.upsertEvents(connectionId, fullBatch.events(), SyncSourceAttribution.WEBHOOK);
                if (fullBatch.nextCursor() != null) {
                    connectionWriteService.advanceProviderCursor(
                            connection.getId(), null, fullBatch.nextCursor(), Instant.now(), "webhook_full_cursor_advance");
                    log.info("cursor_advance_attempt provider={} source=WEBHOOK connectionId={} advanced={} expectedCursorPresent=false nextCursorPresent={}",
                            provider,
                            connection.getId(), true, true);
                }
                meterRegistry.counter("calendar.sync.replay_recovery_action.total", "provider", provider, "action", "webhook_full_recovery")
                        .increment();
                slotCacheVersionService.bumpVersion(connection.getUserId());
                connectionWriteService.markActive(
                        connection.getId(),
                        connection.getLastTokenExpiresAt(),
                        connection.getLastSyncedAt(),
                        "webhook_full_resync_success");
                log.info("calendar_webhook_ingested provider={} providerEventId={} connectionId={} mode=full_resync",
                        provider, normalizedProviderEventId, connectionId);
            } catch (RuntimeException ex) {
                handleWebhookSyncFailure(provider, connection, normalizedProviderEventId, ex);
            }
        } finally {
            MDC.remove("externalEventId");
            MDC.remove("bookingId");
            MDC.remove("causationId");
            MDC.remove("correlationId");
            MDC.remove("syncSource");
        }
    }

    /**
     * Phase 4 R3 fix: status-aware webhook failure handling.
     *
     * Mirrors the F1/F3 pattern from CalendarSyncScheduler:
     * <ul>
     *   <li>Reload the connection to see the status TokenRefresher may have already written
     *       in its own REQUIRES_NEW transaction. REVOKED/ERROR is preserved verbatim — we
     *       must not overwrite a terminal classification with the legacy
     *       "WEBHOOK_SYNC_FAILED" stamp.</li>
     *   <li>Otherwise classify via the typed OAuth category (Phase 2 F6) and call
     *       {@code markFailureWithCategory}, which drives backoff + quarantine.</li>
     *   <li>Swallow the exception for TERMINAL and TRANSIENT categories so the controller
     *       returns 200. The pull-sweep is the deterministic retry mechanism; letting Google
     *       retry independently was the source of the original retry-storm amplification.
     *       Genuinely UNKNOWN errors still propagate so observability surfaces them.</li>
     * </ul>
     */
    private void handleWebhookSyncFailure(String provider,
                                           CalendarConnection connection,
                                           String providerEventId,
                                           RuntimeException ex) {
        CalendarConnection latest = connectionRepository.findById(connection.getId()).orElse(connection);
        CalendarConnectionStatus latestStatus = latest.getStatus();
        String latestErrorCode = latest.getLastErrorCode();

        if (isAlreadyClassified(latestStatus)) {
            meterRegistry.counter("calendar.webhook.terminal_preserved.total",
                            "provider", provider,
                            "status", latestStatus.name(),
                            "errorCode", safeTag(latestErrorCode))
                    .increment();
            log.warn("calendar_webhook_terminal_preserved provider={} providerEventId={} connectionId={} preservedStatus={} preservedErrorCode={} ingestErrorClass={}",
                    provider, providerEventId, connection.getId(), latestStatus, latestErrorCode,
                    ex.getClass().getSimpleName());
            // Swallow — terminal/already-classified. Google should not retry; the pull sweep
            // (or reconnect) is the only way forward.
            return;
        }

        OAuthErrorCategory category = categoryOf(ex);
        String errorCode = errorCodeOf(ex);
        connectionWriteService.markFailureWithCategory(
                connection.getId(),
                category,
                errorCode,
                Instant.now(),
                "webhook_sync_failure");
        meterRegistry.counter("calendar.webhook.failure.total",
                        "provider", provider,
                        "category", category.name(),
                        "errorCode", safeTag(errorCode))
                .increment();
        log.warn("calendar_webhook_ingest_failed provider={} providerEventId={} connectionId={} category={} errorCode={}",
                provider, providerEventId, connection.getId(), category, errorCode, ex);

        // Only propagate UNKNOWN failures. TERMINAL is dead and Google retries are wasted;
        // TRANSIENT has next_retry_at scheduled and the pull sweep will pick it up — Google
        // re-delivering the same notification adds no value and risks retry amplification.
        if (category == OAuthErrorCategory.UNKNOWN) {
            throw ex;
        }
    }

    private static boolean isAlreadyClassified(CalendarConnectionStatus status) {
        return status == CalendarConnectionStatus.REVOKED || status == CalendarConnectionStatus.ERROR;
    }

    private static OAuthErrorCategory categoryOf(RuntimeException ex) {
        if (ex instanceof CalendarClientException cce) {
            OAuthError err = cce.getOAuthError();
            if (err != null) {
                return err.category();
            }
            int status = cce.getStatusCode();
            if (status == 401 || status == 403) return OAuthErrorCategory.TERMINAL;
            if (status == 429 || status >= 500) return OAuthErrorCategory.TRANSIENT;
            return OAuthErrorCategory.UNKNOWN;
        }
        return OAuthErrorCategory.UNKNOWN;
    }

    private static String errorCodeOf(RuntimeException ex) {
        if (ex instanceof CalendarClientException cce && cce.getOAuthError() != null) {
            return cce.getOAuthError().stableCode();
        }
        String message = ex.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("invalid_grant")) return "invalid_grant";
            if (normalized.contains("invalid_token")) return "invalid_token";
            if (normalized.contains("unauthorized")) return "unauthorized";
        }
        return "WEBHOOK_SYNC_FAILED";
    }

    private static String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static void putIfAbsent(String key, String value) {
        if (MDC.get(key) == null) {
            MDC.put(key, value == null ? "" : value);
        }
    }
}
