package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.CalendarWebhookReplayFixture;
import io.bunnycal.calendar.replay.WebhookDeliveryMetadata;
import io.bunnycal.calendar.repository.CalendarWebhookReplayFixtureRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.bunnycal.sync.state.SyncSourceAttribution;

@Service
public class CalendarWebhookReplayCaptureService {
    private static final Logger log = LoggerFactory.getLogger(CalendarWebhookReplayCaptureService.class);

    private final CalendarWebhookReplayFixtureRepository repository;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    public CalendarWebhookReplayCaptureService(CalendarWebhookReplayFixtureRepository repository,
                                               MeterRegistry meterRegistry,
                                               @Value("${calendar.webhook.replay-capture.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }

    @Transactional
    public void capture(String provider,
                        UUID connectionId,
                        String providerEventId,
                        String rawPayload,
                        String deliveryKey,
                        String payloadHash,
                        boolean duplicate,
                        WebhookDeliveryMetadata metadata) {
        if (!enabled) {
            return;
        }
        CalendarWebhookReplayFixture fixture = new CalendarWebhookReplayFixture();
        fixture.setProvider(normalizeProvider(provider));
        fixture.setConnectionId(connectionId);
        fixture.setProviderEventId(providerEventId == null ? "" : providerEventId);
        fixture.setRawPayload(rawPayload);
        fixture.setDeliveryKey(deliveryKey == null ? "" : deliveryKey);
        fixture.setPayloadHash(payloadHash);
        fixture.setDedupResult(duplicate ? "DUPLICATE" : "FIRST_SEEN");
        fixture.setProviderUpdatedAt(metadata == null ? null : metadata.providerUpdatedAt());
        fixture.setProviderEtag(metadata == null ? null : metadata.providerEtag());
        fixture.setProviderSequence(metadata == null ? null : metadata.providerSequence());
        fixture.setDeliveryId(metadata == null ? null : metadata.deliveryId());
        fixture.setSourceAttribution((metadata == null || metadata.sourceAttribution() == null)
                ? SyncSourceAttribution.WEBHOOK.name()
                : metadata.sourceAttribution().name());
        fixture.setRecurringHint(inferRecurringHint(rawPayload));
        fixture.setCorrelationId(MDC.get("correlationId"));
        fixture.setCausationId(MDC.get("causationId"));
        fixture.setCapturedAt(Instant.now());
        CalendarWebhookReplayFixture saved = repository.save(fixture);
        log.info("webhook_replay_fixture_captured fixtureId={} arrivalIndex={} provider={} source={} connectionId={} providerEventId={} dedupResult={} correlationId={} causationId={}",
                saved.getId(),
                saved.getArrivalIndex(),
                saved.getProvider(),
                saved.getSourceAttribution(),
                saved.getConnectionId(),
                saved.getProviderEventId(),
                saved.getDedupResult(),
                saved.getCorrelationId(),
                saved.getCausationId());

        meterRegistry.counter("webhook_replay_fixture_captured_total",
                "provider", fixture.getProvider(),
                "dedup_result", fixture.getDedupResult()).increment();
        if (fixture.isRecurringHint()) {
            meterRegistry.counter("webhook_replay_fixture_recurring_total",
                    "provider", fixture.getProvider()).increment();
        }
    }

    private static boolean inferRecurringHint(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }
        return payload.contains("\"recurringEventId\"")
                || payload.contains("\"recurrence\"")
                || payload.contains("\"originalStartTime\"");
    }

    private static String normalizeProvider(String provider) {
        return provider == null ? "UNKNOWN" : provider.trim().toUpperCase(Locale.ROOT);
    }
}
