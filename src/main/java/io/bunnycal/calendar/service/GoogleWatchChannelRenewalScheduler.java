package io.bunnycal.calendar.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.GoogleApiClient;
import io.bunnycal.calendar.config.CalendarWebhookProperties;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GoogleWatchChannelRenewalScheduler {
    private static final Logger log = LoggerFactory.getLogger(GoogleWatchChannelRenewalScheduler.class);
    private static final CalendarProviderType GOOGLE_PROVIDER = CalendarProviderType.GOOGLE;
    private static final Duration WATCH_CHANNEL_TTL = Duration.ofDays(7);

    private final CalendarConnectionRepository connectionRepository;
    private final TokenRefresher tokenRefresher;
    private final GoogleApiClient googleApiClient;
    private final CalendarConnectionWriteService connectionWriteService;
    private final SlotCacheVersionService slotCacheVersionService;
    private final MeterRegistry meterRegistry;
    private final CalendarWebhookProperties webhookProperties;
    private final Duration renewalLeadTime;

    public GoogleWatchChannelRenewalScheduler(
            CalendarConnectionRepository connectionRepository,
            TokenRefresher tokenRefresher,
            GoogleApiClient googleApiClient,
            CalendarConnectionWriteService connectionWriteService,
            SlotCacheVersionService slotCacheVersionService,
            MeterRegistry meterRegistry,
            CalendarWebhookProperties webhookProperties,
            @Value("${calendar.webhook.renewal.lead-time:PT24H}") Duration renewalLeadTime) {
        this.connectionRepository = connectionRepository;
        this.tokenRefresher = tokenRefresher;
        this.googleApiClient = googleApiClient;
        this.connectionWriteService = connectionWriteService;
        this.slotCacheVersionService = slotCacheVersionService;
        this.meterRegistry = meterRegistry;
        this.webhookProperties = webhookProperties;
        this.renewalLeadTime = renewalLeadTime;

        Gauge.builder("webhook_channel_age_seconds", this, GoogleWatchChannelRenewalScheduler::maxChannelAgeSeconds)
                .description("Age of the oldest Google watch channel relative to nominal TTL")
                .tags(Tags.of("provider", "google"))
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${calendar.webhook.renewal.fixed-delay-ms:900000}")
    @SchedulerLock(
            name = "google-watch-renewal",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S"
    )
    public void renewExpiringChannels() {
        String googleWebhookAddress = webhookProperties.getProvider().getGoogle().getAddress();
        String googleWebhookToken = webhookProperties.getSharedSecret();
        if (!webhookProperties.isProviderWebhookEnabled(GOOGLE_PROVIDER)) {
            return;
        }
        if (googleWebhookAddress == null || googleWebhookAddress.isBlank()
                || googleWebhookToken == null || googleWebhookToken.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        Instant renewalThreshold = now.plus(renewalLeadTime);
        // Phase 4 R1 fix: includes NULL-expires-at ACTIVE rows (silently watchless because
        // the initial watchEvents at OAuth time failed). The legacy Spring Data derived
        // query findByProviderAndWebhookChannelExpiresAtBefore excluded NULL rows and left
        // those connections permanently pull-only.
        List<CalendarConnection> candidates = connectionRepository.findActiveRequiringWatchRenewal(
                GOOGLE_PROVIDER, renewalThreshold);

        for (CalendarConnection connection : candidates) {
            boolean wasWatchless = connection.getWebhookChannelId() == null
                    || connection.getWebhookChannelId().isBlank()
                    || connection.getWebhookChannelExpiresAt() == null;
            incrementCounter("webhook_renewal_attempts_total", "outcome", "attempt");
            if (wasWatchless) {
                incrementCounter("calendar.watch.recovery_attempts.total",
                        "reason", connection.getWebhookChannelId() == null ? "missing" : "expired_or_null");
            }
            try {
                renewConnection(connection);
                incrementCounter("webhook_renewal_attempts_total", "outcome", "success");
                if (wasWatchless) {
                    incrementCounter("calendar.watch.recovered.total");
                    log.info("calendar_watch_recovered provider=google connectionId={} userId={} previousChannelExpiresAt={}",
                            connection.getId(), connection.getUserId(), connection.getWebhookChannelExpiresAt());
                }
            } catch (RuntimeException ex) {
                String errorCode = resolveErrorCode(ex);
                incrementCounter("webhook_renewal_attempts_total", "outcome", "failure");
                incrementCounter("webhook_renewal_failures_total", "errorCode", errorCode);
                // Phase 4 R8: record per-connection renewal failure for operator visibility.
                try {
                    connectionWriteService.recordWatchRenewalFailure(
                            connection.getId(), Instant.now(), "google_watch_renewal_failure");
                } catch (RuntimeException trackingEx) {
                    log.warn("calendar_watch_renewal_failure_tracking_failed connectionId={}",
                            connection.getId(), trackingEx);
                }
                log.warn("google_watch_renewal_failed connectionId={} userId={} errorCode={} failureCount={}",
                        connection.getId(), connection.getUserId(), errorCode,
                        connection.getWatchRenewalFailureCount() + 1, ex);
            }
        }
    }

    /**
     * Phase 4 R2 + R9 atomicity fix.
     *
     * <p>Order is now <strong>create-new → swap-DB-with-status-guard → stop-old</strong>.
     * Rationale:
     * <ul>
     *   <li>Old "stop → create → write" had two distinct disaster modes:
     *     (a) crash between stop and create = silent webhook outage for up to 15 min;
     *     (b) crash between create and write = leaked active channel at Google.</li>
     *   <li>New order replaces both with a single benign mode: crash anywhere between
     *     create and stop = the old channel keeps delivering for a short while alongside
     *     the new channel, dedup handles duplicates, and an orphan (the old) gets 200-OK
     *     dropped by the controller after the new channel is persisted.</li>
     * </ul>
     *
     * <p>Residual unavoidable race: a crash between {@code watchEvents} (Google has new
     * channel) and {@code updateWebhookChannelIfActive} leaves an active channel at Google
     * that the DB does not know about. The controller's R4 orphan-200 fix makes this
     * benign (Google retries are absorbed and not amplified); the channel auto-expires
     * after its 7-day TTL.
     */
    private void renewConnection(CalendarConnection connection) {
        String googleWebhookAddress = webhookProperties.getProvider().getGoogle().getAddress();
        String googleWebhookToken = webhookProperties.getSharedSecret();
        // 1. Create the new channel FIRST.
        GoogleApiClient.WatchChannel renewed = tokenRefresher.executeWithValidToken(connection.getId(), accessToken ->
                googleApiClient.watchEvents(accessToken, googleWebhookAddress, googleWebhookToken));

        // 2. Atomic swap with status guard. If the row is no longer ACTIVE (disconnect ran
        //    between step 1 and step 2), do NOT write — and stop the just-created channel.
        Instant attemptAt = Instant.now();
        CalendarConnection updated = connectionWriteService.updateWebhookChannelIfActive(
                connection.getId(),
                renewed.channelId(),
                renewed.resourceId(),
                renewed.expiration(),
                attemptAt,
                "google_watch_renewal");
        if (updated == null) {
            // Disconnect (or any other terminal transition) won the race. Stop the channel
            // we just created so we don't leak it at Google's end.
            log.warn("calendar_watch_renewal_status_guard_skipped connectionId={} createdChannelId={} createdResourceId={}",
                    connection.getId(), renewed.channelId(), renewed.resourceId());
            incrementCounter("calendar.watch.renewal_status_guard.total");
            stopChannelBestEffort(connection.getId(), renewed.channelId(), renewed.resourceId());
            return;
        }

        // 3. Stop the OLD channel last (best-effort; we've already swapped, so a failure
        //    here just leaves the old channel delivering until its TTL — dedup'd by us and
        //    eventually 200-OK dropped by the controller as orphan).
        String oldChannelId = connection.getWebhookChannelId();
        String oldResourceId = connection.getWebhookResourceId();
        if (oldChannelId != null && !oldChannelId.isBlank()
                && oldResourceId != null && !oldResourceId.isBlank()) {
            stopChannelBestEffort(connection.getId(), oldChannelId, oldResourceId);
        }

        slotCacheVersionService.bumpVersionAfterCommit(connection.getUserId());
    }

    private void stopChannelBestEffort(java.util.UUID connectionId, String channelId, String resourceId) {
        try {
            tokenRefresher.executeWithValidToken(connectionId, accessToken -> {
                googleApiClient.stopWatchChannel(accessToken, channelId, resourceId);
                return null;
            });
        } catch (RuntimeException ex) {
            log.warn("google_watch_stop_failed connectionId={} channelId={}", connectionId, channelId, ex);
        }
    }

    private void incrementCounter(String meterName, String... tags) {
        // Tags are passed as alternating key/value pairs.
        meterRegistry.counter(meterName, Tags.of(tags).and("provider", "google")).increment();
    }

    double maxChannelAgeSeconds() {
        Instant now = Instant.now();
        return connectionRepository.findByProviderAndWebhookChannelExpiresAtIsNotNull(GOOGLE_PROVIDER).stream()
                .map(CalendarConnection::getWebhookChannelExpiresAt)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(expiresAt -> Duration.between(expiresAt.minus(WATCH_CHANNEL_TTL), now).toSeconds())
                .max()
                .orElse(0.0d);
    }

    private static String resolveErrorCode(RuntimeException ex) {
        if (ex instanceof CalendarClientException clientEx) {
            return "HTTP_" + clientEx.getStatusCode();
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return ex.getClass().getSimpleName();
    }
}
