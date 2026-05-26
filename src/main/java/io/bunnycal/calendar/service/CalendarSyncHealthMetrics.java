package io.bunnycal.calendar.service;

import io.bunnycal.calendar.config.CalendarWebhookProperties;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 3 aggregate operational gauges. Refresh on a fixed cadence and publish
 * single-cardinality (provider-tagged) Micrometer gauges so dashboards can alert without
 * exploding the metric space.
 *
 * <p>Per-connection gauges live in {@link WebhookFreshnessMetrics}; this class is for
 * fleet-level health indicators.
 */
@Component
public class CalendarSyncHealthMetrics {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncHealthMetrics.class);

    private final CalendarConnectionRepository repository;
    private final CalendarWebhookProperties webhookProperties;
    private final Duration staleSyncThreshold;

    private final AtomicLong dueQueueSize = new AtomicLong(0L);
    private final Map<CalendarProviderType, AtomicLong> activeHealthyWatches = new EnumMap<>(CalendarProviderType.class);
    private final Map<CalendarProviderType, AtomicLong> activeWithoutHealthyWatch = new EnumMap<>(CalendarProviderType.class);
    private final Map<CalendarProviderType, AtomicLong> staleActiveConnections = new EnumMap<>(CalendarProviderType.class);
    private final Map<CalendarProviderType, AtomicLong> pullOnlyActiveConnections = new EnumMap<>(CalendarProviderType.class);
    private final Map<CalendarProviderType, AtomicLong> webhookProviderEnabled = new EnumMap<>(CalendarProviderType.class);

    public CalendarSyncHealthMetrics(CalendarConnectionRepository repository,
                                     CalendarWebhookProperties webhookProperties,
                                     MeterRegistry meterRegistry,
                                     @Value("${calendar.sync.health.stale-active-threshold:PT1H}") Duration staleSyncThreshold) {
        this.repository = repository;
        this.webhookProperties = webhookProperties;
        this.staleSyncThreshold = staleSyncThreshold;

        Gauge.builder("calendar.sync.due_queue.size", dueQueueSize, AtomicLong::doubleValue)
                .description("Connections currently eligible for the next sweep (ACTIVE/SYNCING + due retries).")
                .register(meterRegistry);

        for (CalendarProviderType provider : new CalendarProviderType[] {
                CalendarProviderType.GOOGLE, CalendarProviderType.MICROSOFT }) {
            String tag = provider.name().toLowerCase(Locale.ROOT);

            AtomicLong watches = new AtomicLong(0L);
            activeHealthyWatches.put(provider, watches);
            Gauge.builder("calendar.watch.active.count", watches, AtomicLong::doubleValue)
                    .tag("provider", tag)
                    .description("ACTIVE connections whose webhook channel has not yet expired.")
                    .register(meterRegistry);

            AtomicLong missingWatches = new AtomicLong(0L);
            activeWithoutHealthyWatch.put(provider, missingWatches);
            Gauge.builder("calendar.watch.missing.count", missingWatches, AtomicLong::doubleValue)
                    .tag("provider", tag)
                    .description("ACTIVE connections with NULL or expired webhook channel — strong drift indicator.")
                    .register(meterRegistry);

            AtomicLong stale = new AtomicLong(0L);
            staleActiveConnections.put(provider, stale);
            Gauge.builder("calendar.connection.stale_active.count", stale, AtomicLong::doubleValue)
                    .tag("provider", tag)
                    .description("ACTIVE connections whose last_synced_at is older than the stale threshold.")
                    .register(meterRegistry);

            AtomicLong pullOnly = new AtomicLong(0L);
            pullOnlyActiveConnections.put(provider, pullOnly);
            Gauge.builder("calendar.sync.pull_only.active.count", pullOnly, AtomicLong::doubleValue)
                    .tag("provider", tag)
                    .description("ACTIVE connections currently operating pull-only (webhooks disabled or watch/subscription missing).")
                    .register(meterRegistry);

            AtomicLong providerEnabled = new AtomicLong(0L);
            webhookProviderEnabled.put(provider, providerEnabled);
            Gauge.builder("calendar.webhook.provider.enabled", providerEnabled, AtomicLong::doubleValue)
                    .tag("provider", tag)
                    .description("Webhook provider enable flag (1=enabled, 0=disabled after global+provider precedence).")
                    .register(meterRegistry);
        }
    }

    @Scheduled(fixedDelayString = "${calendar.sync.health.refresh-interval-ms:60000}")
    public void refresh() {
        Instant now = Instant.now();
        Instant staleThreshold = now.minus(staleSyncThreshold);
        try {
            dueQueueSize.set(repository.countDueForSync(now));
            for (CalendarProviderType provider : activeHealthyWatches.keySet()) {
                boolean providerWebhookEnabled = webhookProperties.isProviderWebhookEnabled(provider);
                long activeConnections = repository.countActiveConnections(provider);
                long watchlessConnections = repository.countActiveWithoutHealthyWatch(provider, now);
                activeHealthyWatches.get(provider).set(repository.countActiveHealthyWatches(provider, now));
                activeWithoutHealthyWatch.get(provider).set(watchlessConnections);
                staleActiveConnections.get(provider).set(repository.countStaleActive(provider, staleThreshold));
                pullOnlyActiveConnections.get(provider).set(providerWebhookEnabled ? watchlessConnections : activeConnections);
                webhookProviderEnabled.get(provider).set(providerWebhookEnabled ? 1L : 0L);
            }
        } catch (RuntimeException ex) {
            // Never let metric refresh interfere with the actual sync workload.
            log.warn("calendar_sync_health_metrics_refresh_failed", ex);
        }
    }
}
