package io.bunnycal.calendar.service;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.MicrosoftApiClient;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
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
    private final String webhookClientState;
    private final String webhookAddress;
    private final Duration renewalLeadTime;
    private final long ttlSeconds;

    public MicrosoftWatchChannelRenewalScheduler(CalendarConnectionRepository connectionRepository,
                                                 CalendarConnectionWriteService connectionWriteService,
                                                 MicrosoftApiClient microsoftApiClient,
                                                 TokenRefresher tokenRefresher,
                                                 MeterRegistry meterRegistry,
                                                 @Value("${calendar.webhook.shared-secret:}") String webhookClientState,
                                                 @Value("${calendar.webhook.provider.microsoft.address:http://localhost:8080/integrations/calendar/webhooks/microsoft}") String webhookAddress,
                                                 @Value("${calendar.webhook.renewal.lead-time:PT24H}") Duration renewalLeadTime,
                                                 @Value("${calendar.webhook.provider.microsoft.ttl-seconds:7200}") long ttlSeconds) {
        this.connectionRepository = connectionRepository;
        this.connectionWriteService = connectionWriteService;
        this.microsoftApiClient = microsoftApiClient;
        this.tokenRefresher = tokenRefresher;
        this.meterRegistry = meterRegistry;
        this.webhookClientState = webhookClientState;
        this.webhookAddress = webhookAddress;
        this.renewalLeadTime = renewalLeadTime;
        this.ttlSeconds = Math.max(900L, ttlSeconds);
    }

    @Scheduled(fixedDelayString = "${calendar.webhook.renewal.fixed-delay-ms:900000}")
    @SchedulerLock(name = "microsoft-watch-renewal", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void renewExpiringChannels() {
        if (webhookAddress == null || webhookAddress.isBlank() || webhookClientState == null || webhookClientState.isBlank()) {
            return;
        }
        Instant threshold = Instant.now().plus(renewalLeadTime);
        List<CalendarConnection> candidates =
                connectionRepository.findByProviderAndWebhookChannelExpiresAtBefore(MICROSOFT_PROVIDER, threshold);
        for (CalendarConnection connection : candidates) {
            if (connection.getStatus() != CalendarConnectionStatus.ACTIVE) {
                continue;
            }
            try {
                MicrosoftApiClient.WebhookSubscription subscription = tokenRefresher.executeWithValidToken(
                        connection.getId(),
                        accessToken -> renewOrCreate(accessToken, connection));
                connectionWriteService.updateWebhookChannel(
                        connection.getId(),
                        subscription.subscriptionId(),
                        subscription.resourceId(),
                        subscription.expiresAt(),
                        "microsoft_webhook_renewal");
                meterRegistry.counter("webhook_renewal_attempts_total", "provider", "microsoft", "outcome", "success").increment();
            } catch (RuntimeException ex) {
                meterRegistry.counter("webhook_renewal_attempts_total", "provider", "microsoft", "outcome", "failure").increment();
                log.warn("microsoft_watch_renewal_failed connectionId={} userId={}", connection.getId(), connection.getUserId(), ex);
            }
        }
    }

    private MicrosoftApiClient.WebhookSubscription renewOrCreate(String accessToken, CalendarConnection connection) {
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        String subscriptionId = connection.getWebhookChannelId();
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            return microsoftApiClient.renewEventSubscription(accessToken, subscriptionId, expiresAt);
        }
        return microsoftApiClient.createEventSubscription(accessToken, webhookAddress, webhookClientState, expiresAt);
    }
}
