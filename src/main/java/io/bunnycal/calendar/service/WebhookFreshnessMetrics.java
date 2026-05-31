package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarWebhookEventRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Provider-level webhook freshness observability.
 *
 * <p><strong>Phase 4 R7 fix:</strong> the previous incarnation registered one Micrometer
 * gauge per connection, tagged by {@code connection_id}, which is unbounded cardinality at
 * scale (10k+ connections explode the metric store). The replacement publishes
 * provider-aggregated metrics with bounded cardinality (just {@code provider}):
 *
 * <ul>
 *   <li>{@code calendar.webhook.seconds_since_last_delivery} — DistributionSummary;
 *       each refresh records each connection's age as a sample, so dashboards can derive
 *       p50/p95/p99 across the fleet.</li>
 *   <li>{@code calendar.webhook.max_age_seconds} — gauge of the worst single connection's
 *       age. Useful as an alert anchor.</li>
 *   <li>{@code calendar.webhook.delivery_stale_count} — gauge of connections whose age
 *       exceeds a configurable stale threshold. Direct alert source.</li>
 * </ul>
 */
@Component
public class WebhookFreshnessMetrics {

    private static final Logger log = LoggerFactory.getLogger(WebhookFreshnessMetrics.class);
    private static final List<CalendarProviderType> TRACKED_PROVIDERS = List.of(
            CalendarProviderType.GOOGLE, CalendarProviderType.MICROSOFT);

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarWebhookEventRepository webhookEventRepository;
    private final MeterRegistry meterRegistry;
    private final Duration staleThreshold;

    private final Map<CalendarProviderType, DistributionSummary> ageSummaries = new EnumMap<>(CalendarProviderType.class);
    private final Map<CalendarProviderType, AtomicLong> maxAgeSeconds = new EnumMap<>(CalendarProviderType.class);
    private final Map<CalendarProviderType, AtomicLong> staleCount = new EnumMap<>(CalendarProviderType.class);

    public WebhookFreshnessMetrics(CalendarConnectionRepository connectionRepository,
                                   CalendarWebhookEventRepository webhookEventRepository,
                                   MeterRegistry meterRegistry,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${calendar.webhook.freshness.stale-threshold:PT15M}") Duration staleThreshold) {
        this.connectionRepository = connectionRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.meterRegistry = meterRegistry;
        this.staleThreshold = staleThreshold;

        for (CalendarProviderType provider : TRACKED_PROVIDERS) {
            String tag = provider.name().toLowerCase(Locale.ROOT);
            ageSummaries.put(provider, DistributionSummary.builder("calendar.webhook.seconds_since_last_delivery")
                    .tag("provider", tag)
                    .description("Age in seconds of each ACTIVE connection's most recent webhook delivery (or last_synced_at fallback).")
                    .publishPercentileHistogram()
                    .register(meterRegistry));

            AtomicLong max = new AtomicLong(0L);
            maxAgeSeconds.put(provider, max);
            Gauge.builder("calendar.webhook.max_age_seconds", max, AtomicLong::doubleValue)
                    .tag("provider", tag)
                    .description("Age of the single ACTIVE connection with the oldest webhook delivery.")
                    .register(meterRegistry);

            AtomicLong stale = new AtomicLong(0L);
            staleCount.put(provider, stale);
            Gauge.builder("calendar.webhook.delivery_stale_count", stale, AtomicLong::doubleValue)
                    .tag("provider", tag)
                    .description("ACTIVE connections whose most recent webhook delivery is older than the stale threshold.")
                    .register(meterRegistry);
        }
    }

    @Scheduled(fixedDelayString = "${calendar.webhook.freshness.metrics-interval-ms:60000}")
    public void refresh() {
        for (CalendarProviderType provider : TRACKED_PROVIDERS) {
            try {
                refreshProvider(provider);
            } catch (RuntimeException ex) {
                log.warn("calendar_webhook_freshness_refresh_failed provider={}", provider, ex);
            }
        }
    }

    private void refreshProvider(CalendarProviderType provider) {
        DistributionSummary summary = ageSummaries.get(provider);
        long max = 0L;
        long staleN = 0L;
        Instant now = Instant.now();
        long staleSeconds = staleThreshold.toSeconds();
        // Sweep ACTIVE connections that have a webhook channel set (even if expired).
        // findActiveRequiringWatchRenewal(provider, FAR_FUTURE) returns every ACTIVE row
        // with NULL or any-future expiration — i.e., the entire ACTIVE population. Reusing
        // it avoids a new repo method just for metrics. Filter to those with a non-null
        // last_synced_at OR a recorded webhook event.
        Instant farFuture = now.plus(Duration.ofDays(3650));
        for (CalendarConnection connection : connectionRepository.findActiveRequiringWatchRenewal(provider, farFuture)) {
            if (connection.getStatus() != CalendarConnectionStatus.ACTIVE) {
                continue;
            }
            Instant latestDelivery = webhookEventRepository.findMaxReceivedAtByConnectionId(connection.getId())
                    .orElse(connection.getLastSyncedAt());
            if (latestDelivery == null) {
                continue;
            }
            long ageSeconds = Math.max(0L, Duration.between(latestDelivery, now).toSeconds());
            summary.record(ageSeconds);
            if (ageSeconds > max) {
                max = ageSeconds;
            }
            if (ageSeconds > staleSeconds) {
                staleN++;
            }
        }
        maxAgeSeconds.get(provider).set(max);
        staleCount.get(provider).set(staleN);
    }
}
