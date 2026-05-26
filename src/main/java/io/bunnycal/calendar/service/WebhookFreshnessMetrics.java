package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarWebhookEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WebhookFreshnessMetrics {
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarWebhookEventRepository webhookEventRepository;
    private final MeterRegistry meterRegistry;
    private final Map<UUID, AtomicReference<Double>> perConnectionAgeSeconds = new ConcurrentHashMap<>();

    public WebhookFreshnessMetrics(CalendarConnectionRepository connectionRepository,
                                   CalendarWebhookEventRepository webhookEventRepository,
                                   MeterRegistry meterRegistry) {
        this.connectionRepository = connectionRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${calendar.webhook.freshness.metrics-interval-ms:60000}")
    public void refresh() {
        for (CalendarConnection connection : connectionRepository.findByProviderAndWebhookChannelExpiresAtIsNotNull(CalendarProviderType.GOOGLE)) {
            if (connection.getStatus() != CalendarConnectionStatus.ACTIVE) {
                continue;
            }
            Instant latestDelivery = webhookEventRepository.findMaxReceivedAtByConnectionId(connection.getId())
                    .orElse(connection.getLastSyncedAt());
            if (latestDelivery == null) {
                continue;
            }
            double ageSeconds = Math.max(0d, Duration.between(latestDelivery, Instant.now()).toSeconds());
            AtomicReference<Double> gaugeValue = perConnectionAgeSeconds.computeIfAbsent(connection.getId(), id -> {
                AtomicReference<Double> value = new AtomicReference<>(0d);
                Gauge.builder("webhook_seconds_since_last_delivery", value, AtomicReference::get)
                        .tag("provider", "google")
                        .tag("connection_id", id.toString())
                        .register(meterRegistry);
                return value;
            });
            gaugeValue.set(ageSeconds);
        }
    }
}

