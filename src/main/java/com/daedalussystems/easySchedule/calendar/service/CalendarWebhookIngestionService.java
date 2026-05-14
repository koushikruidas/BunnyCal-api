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
    private final boolean webhookEnabled;
    private final boolean googleWebhookEnabled;
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
            @Value("${calendar.webhook.provider.google.enabled:false}") boolean googleWebhookEnabled) {
        this.dedupService = dedupService;
        this.connectionRepository = connectionRepository;
        this.syncClient = syncClient;
        this.ingestionService = ingestionService;
        this.replayCaptureService = replayCaptureService;
        this.connectionWriteService = connectionWriteService;
        this.slotCacheVersionService = slotCacheVersionService;
        this.webhookEnabled = webhookEnabled;
        this.googleWebhookEnabled = googleWebhookEnabled;
        this.webhookIngestTotal = Counter.builder("webhook_ingest_total").register(meterRegistry);
        this.webhookDuplicateTotal = Counter.builder("webhook_duplicate_total").register(meterRegistry);
        this.reconciliationConflictTotal = Counter.builder("reconciliation_conflict_total").register(meterRegistry);
    }

    @Transactional
    public void ingestGoogle(UUID connectionId, String providerEventId, String rawPayload) {
        ingestGoogle(connectionId, providerEventId, rawPayload, WebhookDeliveryMetadata.empty());
    }

    @Transactional
    public void ingestGoogle(UUID connectionId, String providerEventId, String rawPayload, WebhookDeliveryMetadata deliveryMetadata) {
        if (!webhookEnabled || !googleWebhookEnabled) {
            throw new CustomException(ErrorCode.FORBIDDEN, "Calendar webhook ingestion is disabled.");
        }
        if (connectionId == null || providerEventId == null || providerEventId.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "connectionId and providerEventId are required.");
        }
        putIfAbsent("correlationId", providerEventId);
        putIfAbsent("causationId", providerEventId);
        putIfAbsent("bookingId", "");
        putIfAbsent("externalEventId", providerEventId);
        putIfAbsent("syncSource", SyncSourceAttribution.WEBHOOK.name());
        try {
            webhookIngestTotal.increment();
            CalendarWebhookDedupService.DedupOutcome dedupOutcome =
                    dedupService.checkAndRecord("google", connectionId, providerEventId, rawPayload);
            replayCaptureService.capture(
                    "google",
                    connectionId,
                    providerEventId,
                    rawPayload,
                    dedupOutcome.deliveryKey(),
                    dedupOutcome.payloadHash(),
                    !dedupOutcome.firstSeen(),
                    deliveryMetadata);
            if (!dedupOutcome.firstSeen()) {
                webhookDuplicateTotal.increment();
                reconciliationConflictTotal.increment();
                log.info("calendar_webhook_duplicate provider={} providerEventId={} connectionId={}",
                        "google", providerEventId, connectionId);
                return;
            }

            CalendarConnection connection = connectionRepository.findById(connectionId)
                    .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Calendar connection not found."));
            CalendarConnectionStatus prevStatus = connection.getStatus();
            try {
                ingestionService.upsertEvents(connectionId, syncClient.fetchIncremental(connection), SyncSourceAttribution.WEBHOOK);
                if (prevStatus != CalendarConnectionStatus.ACTIVE) {
                    slotCacheVersionService.bumpVersion(connection.getUserId());
                }
                connectionWriteService.markActive(
                        connection.getId(),
                        connection.getLastTokenExpiresAt(),
                        connection.getLastSyncedAt(),
                        "webhook_incremental_success");
                log.info("calendar_webhook_ingested provider={} providerEventId={} connectionId={} mode=incremental",
                        "google", providerEventId, connectionId);
            } catch (ExternalCalendarSyncClient.SyncTokenInvalidException invalid) {
                ingestionService.upsertEvents(connectionId, syncClient.fetchFull(connection), SyncSourceAttribution.WEBHOOK);
                slotCacheVersionService.bumpVersion(connection.getUserId());
                connectionWriteService.markActive(
                        connection.getId(),
                        connection.getLastTokenExpiresAt(),
                        connection.getLastSyncedAt(),
                        "webhook_full_resync_success");
                log.info("calendar_webhook_ingested provider={} providerEventId={} connectionId={} mode=full_resync",
                        "google", providerEventId, connectionId);
            } catch (RuntimeException ex) {
                connectionWriteService.markFailure(
                        connection.getId(),
                        CalendarConnectionStatus.FAILED,
                        "WEBHOOK_SYNC_FAILED",
                        Instant.now(),
                        "webhook_sync_failure");
                log.warn("calendar_webhook_ingest_failed provider={} providerEventId={} connectionId={}",
                        "google", providerEventId, connectionId, ex);
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
