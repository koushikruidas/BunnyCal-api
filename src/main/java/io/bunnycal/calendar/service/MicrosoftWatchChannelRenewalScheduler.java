package io.bunnycal.calendar.service;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.MicrosoftApiClient;
import io.bunnycal.calendar.config.CalendarWebhookProperties;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.micrometer.core.instrument.MeterRegistry;
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
public class MicrosoftWatchChannelRenewalScheduler {
    private static final Logger log = LoggerFactory.getLogger(MicrosoftWatchChannelRenewalScheduler.class);
    private static final CalendarProviderType MICROSOFT_PROVIDER = CalendarProviderType.MICROSOFT;

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarConnectionWriteService connectionWriteService;
    private final MicrosoftApiClient microsoftApiClient;
    private final TokenRefresher tokenRefresher;
    private final MeterRegistry meterRegistry;
    private final CalendarWebhookProperties webhookProperties;
    private final Duration renewalLeadTime;
    private final long minTtlSeconds;

    public MicrosoftWatchChannelRenewalScheduler(CalendarConnectionRepository connectionRepository,
                                                 CalendarConnectionWriteService connectionWriteService,
                                                 MicrosoftApiClient microsoftApiClient,
                                                 TokenRefresher tokenRefresher,
                                                 MeterRegistry meterRegistry,
                                                 CalendarWebhookProperties webhookProperties,
                                                 @Value("${calendar.webhook.renewal.lead-time:PT24H}") Duration renewalLeadTime,
                                                 @Value("${calendar.webhook.provider.microsoft.ttl-seconds:7200}") long ttlSeconds) {
        this.connectionRepository = connectionRepository;
        this.connectionWriteService = connectionWriteService;
        this.microsoftApiClient = microsoftApiClient;
        this.tokenRefresher = tokenRefresher;
        this.meterRegistry = meterRegistry;
        this.webhookProperties = webhookProperties;
        this.renewalLeadTime = renewalLeadTime;
        this.minTtlSeconds = Math.max(900L, ttlSeconds);
    }

    /**
     * Phase 4 R12 fix: when {@code renewalLeadTime} > {@code ttlSeconds}, every renewal
     * sweep would re-renew every subscription on every tick (and risk MS throttling).
     * Clamp the effective lead time to at most half the TTL so we renew at most twice per
     * TTL window regardless of misconfiguration.
     */
    Duration effectiveRenewalLeadTime() {
        long ttlSeconds = effectiveTtlSeconds();
        Duration halfTtl = Duration.ofSeconds(ttlSeconds / 2);
        return renewalLeadTime.compareTo(halfTtl) > 0 ? halfTtl : renewalLeadTime;
    }

    @Scheduled(fixedDelayString = "${calendar.webhook.renewal.fixed-delay-ms:900000}")
    @SchedulerLock(name = "microsoft-watch-renewal", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void renewExpiringChannels() {
        String webhookAddress = webhookProperties.getProvider().getMicrosoft().getAddress();
        String webhookClientState = webhookProperties.getSharedSecret();
        if (!webhookProperties.isProviderWebhookEnabled(MICROSOFT_PROVIDER)) {
            return;
        }
        if (webhookAddress == null || webhookAddress.isBlank() || webhookClientState == null || webhookClientState.isBlank()) {
            return;
        }
        Instant threshold = Instant.now().plus(effectiveRenewalLeadTime());
        // Phase 4 R1 fix (MS parity): include NULL-expires-at ACTIVE rows so initial-
        // subscription-creation failures are healed by renewal sweeps.
        List<CalendarConnection> candidates =
                connectionRepository.findActiveRequiringWatchRenewal(MICROSOFT_PROVIDER, threshold);
        for (CalendarConnection connection : candidates) {
            boolean wasWatchless = connection.getWebhookChannelId() == null
                    || connection.getWebhookChannelId().isBlank()
                    || connection.getWebhookChannelExpiresAt() == null;
            meterRegistry.counter("webhook_renewal_attempts_total", "provider", "microsoft", "outcome", "attempt").increment();
            if (wasWatchless) {
                meterRegistry.counter("calendar.watch.recovery_attempts.total",
                        "provider", "microsoft",
                        "reason", connection.getWebhookChannelId() == null ? "missing" : "expired_or_null").increment();
            }
            try {
                MicrosoftApiClient.WebhookSubscription subscription = tokenRefresher.executeWithValidToken(
                        connection.getId(),
                        accessToken -> renewOrCreate(accessToken, connection));
                // Phase 4 R10 fix: status-guarded write. If the row went REVOKED between
                // candidate fetch and the API call (disconnect race), do NOT stamp the new
                // subscription onto the revoked row.
                Instant attemptAt = Instant.now();
                CalendarConnection updated = connectionWriteService.updateWebhookChannelIfActive(
                        connection.getId(),
                        subscription.subscriptionId(),
                        subscription.resourceId(),
                        subscription.expiresAt(),
                        attemptAt,
                        "microsoft_webhook_renewal");
                if (updated == null) {
                    meterRegistry.counter("calendar.watch.renewal_status_guard.total",
                            "provider", "microsoft").increment();
                    log.warn("calendar_watch_renewal_status_guard_skipped provider=microsoft connectionId={} subscriptionId={}",
                            connection.getId(), subscription.subscriptionId());
                    // Best-effort: clean up the freshly-created subscription. Renewals
                    // (PATCH) just refreshed an existing one — harmless to leave; creates
                    // genuinely leak so we delete only when this was a fresh create.
                    if (connection.getWebhookChannelId() == null || connection.getWebhookChannelId().isBlank()) {
                        deleteSubscriptionBestEffort(connection.getId(), subscription.subscriptionId());
                    }
                    continue;
                }
                meterRegistry.counter("webhook_renewal_attempts_total", "provider", "microsoft", "outcome", "success").increment();
                if (wasWatchless) {
                    meterRegistry.counter("calendar.watch.recovered.total", "provider", "microsoft").increment();
                    log.info("calendar_watch_recovered provider=microsoft connectionId={} userId={}",
                            connection.getId(), connection.getUserId());
                }
            } catch (RuntimeException ex) {
                meterRegistry.counter("webhook_renewal_attempts_total", "provider", "microsoft", "outcome", "failure").increment();

                // Graph subscriptions do not live forever: once one lapses, Graph garbage-collects
                // it and every PATCH against that id answers 404 ResourceNotFound. Because
                // renewOrCreate branches on "do we have a subscription id?", a dead id keeps
                // selecting the renew path, so the connection retries the same doomed call on every
                // sweep and never recovers (seen in production at failureCount=73). Forget the id so
                // the next sweep takes the create branch and heals itself.
                //
                // Scoped to 404 deliberately. A network blip surfaces as 503 and the subscription is
                // very likely still alive — clearing the id there would orphan it at Graph and leak
                // a subscription on every transient failure.
                if (isSubscriptionGone(ex)) {
                    try {
                        connectionWriteService.updateWebhookChannel(
                                connection.getId(), null, null, null, "microsoft_watch_subscription_gone");
                        meterRegistry.counter("calendar.watch.subscription_gone.total",
                                "provider", "microsoft").increment();
                        log.warn("microsoft_watch_subscription_gone connectionId={} userId={} subscriptionId={} action=cleared_for_recreate",
                                connection.getId(), connection.getUserId(), connection.getWebhookChannelId());
                    } catch (RuntimeException clearEx) {
                        log.warn("microsoft_watch_subscription_clear_failed connectionId={}",
                                connection.getId(), clearEx);
                    }
                }

                try {
                    connectionWriteService.recordWatchRenewalFailure(
                            connection.getId(), Instant.now(), "microsoft_watch_renewal_failure");
                } catch (RuntimeException trackingEx) {
                    log.warn("calendar_watch_renewal_failure_tracking_failed provider=microsoft connectionId={}",
                            connection.getId(), trackingEx);
                }
                log.warn("microsoft_watch_renewal_failed connectionId={} userId={} failureCount={}",
                        connection.getId(), connection.getUserId(),
                        connection.getWatchRenewalFailureCount() + 1, ex);
            }
        }
    }

    /**
     * True when Graph says the subscription we tried to renew no longer exists.
     *
     * <p>Keyed on the 404 status rather than the message body: a transient network failure is
     * classified as 503 and must NOT be treated as "gone", or we would drop a live subscription id
     * and leak an orphaned subscription at Graph on every blip.
     */
    private static boolean isSubscriptionGone(RuntimeException ex) {
        return ex instanceof CalendarClientException calendarEx && calendarEx.getStatusCode() == 404;
    }

    private MicrosoftApiClient.WebhookSubscription renewOrCreate(String accessToken, CalendarConnection connection) {
        String webhookAddress = webhookProperties.getProvider().getMicrosoft().getAddress();
        String webhookClientState = webhookProperties.getSharedSecret();
        long ttlSeconds = effectiveTtlSeconds();
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        String subscriptionId = connection.getWebhookChannelId();
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            return microsoftApiClient.renewEventSubscription(accessToken, subscriptionId, expiresAt);
        }
        return microsoftApiClient.createEventSubscription(accessToken, webhookAddress, webhookClientState, expiresAt);
    }

    private long effectiveTtlSeconds() {
        return Math.max(minTtlSeconds, webhookProperties.getProvider().getMicrosoft().getTtlSeconds());
    }

    private void deleteSubscriptionBestEffort(java.util.UUID connectionId, String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return;
        }
        try {
            tokenRefresher.executeWithValidToken(connectionId, accessToken -> {
                microsoftApiClient.deleteEventSubscription(accessToken, subscriptionId);
                return null;
            });
        } catch (RuntimeException ex) {
            log.warn("microsoft_subscription_delete_after_status_guard_failed connectionId={} subscriptionId={}",
                    connectionId, subscriptionId, ex);
        }
    }
}
