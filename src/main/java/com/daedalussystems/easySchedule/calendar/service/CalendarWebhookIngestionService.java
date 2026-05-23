package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.replay.WebhookDeliveryMetadata;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarWebhookIngestionService {
    private static final Logger log = LoggerFactory.getLogger(CalendarWebhookIngestionService.class);

    private final CalendarWebhookDedupService dedupService;
    private final CalendarConnectionRepository connectionRepository;
    private final ExternalCalendarSyncClient syncClient;
    private final CalendarEventIngestionService ingestionService;
    private final CalendarWebhookReplayCaptureService replayCaptureService;
    private final CalendarConnectionWriteService connectionWriteService;
    private final SlotCacheVersionService slotCacheVersionService;
    private final MeterRegistry meterRegistry;
    private final boolean webhookEnabled;
    private final boolean googleWebhookEnabled;
    private final boolean microsoftWebhookEnabled;
    private final Counter webhookIngestTotal;
    private final Counter webhookDuplicateTotal;
    private final Counter reconciliationConflictTotal;

    public CalendarWebhookIngestionService(
            CalendarWebhookDedupService dedupService,
            CalendarConnectionRepository connectionRepository,
            ExternalCalendarSyncClient syncClient,
            CalendarEventIngestionService ingestionService,
            CalendarWebhookReplayCaptureService replayCaptureService,
            CalendarConnectionWriteService connectionWriteService,
            SlotCacheVersionService slotCacheVersionService,
            MeterRegistry meterRegistry,
            @Value("${calendar.webhook.enabled:false}") boolean webhookEnabled,
            @Value("${calendar.webhook.provider.google.enabled:false}") boolean googleWebhookEnabled,
            @Value("${calendar.webhook.provider.microsoft.enabled:false}") boolean microsoftWebhookEnabled) {
        this.dedupService = dedupService;
        this.connectionRepository = connectionRepository;
        this.syncClient = syncClient;
        this.ingestionService = ingestionService;
        this.replayCaptureService = replayCaptureService;
        this.connectionWriteService = connectionWriteService;
        this.slotCacheVersionService = slotCacheVersionService;
        this.meterRegistry = meterRegistry;
        this.webhookEnabled = webhookEnabled;
        this.googleWebhookEnabled = googleWebhookEnabled;
        this.microsoftWebhookEnabled = microsoftWebhookEnabled;
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
        ingest("microsoft", microsoftWebhookEnabled, connectionId, providerEventId, rawPayload, deliveryMetadata);
    }

    @Transactional
    public void ingestGoogle(UUID connectionId, String providerEventId, String rawPayload, WebhookDeliveryMetadata deliveryMetadata) {
        ingest("google", googleWebhookEnabled, connectionId, providerEventId, rawPayload, deliveryMetadata);
    }

    private void ingest(String provider,
                        boolean providerEnabled,
                        UUID connectionId,
                        String providerEventId,
                        String rawPayload,
                        WebhookDeliveryMetadata deliveryMetadata) {
        if (!webhookEnabled || !providerEnabled) {
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
                connectionWriteService.markFailure(
                        connection.getId(),
                        CalendarConnectionStatus.FAILED,
                        "WEBHOOK_SYNC_FAILED",
                        Instant.now(),
                        "webhook_sync_failure");
                log.warn("calendar_webhook_ingest_failed provider={} providerEventId={} connectionId={}",
                        provider, normalizedProviderEventId, connectionId, ex);
                throw ex;
            }
        } finally {
            MDC.remove("externalEventId");
            MDC.remove("bookingId");
            MDC.remove("causationId");
            MDC.remove("correlationId");
            MDC.remove("syncSource");
        }
    }

    private static void putIfAbsent(String key, String value) {
        if (MDC.get(key) == null) {
            MDC.put(key, value == null ? "" : value);
        }
    }
}
