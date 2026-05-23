package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.auth.TokenCipher;
import com.daedalussystems.easySchedule.calendar.client.MicrosoftApiClient;
import com.daedalussystems.easySchedule.calendar.client.TokenRefreshResult;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
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
    private final TokenCipher tokenCipher;
    private final MeterRegistry meterRegistry;
    private final String webhookClientState;
    private final String webhookAddress;
    private final Duration renewalLeadTime;
    private final long ttlSeconds;

    public MicrosoftWatchChannelRenewalScheduler(CalendarConnectionRepository connectionRepository,
                                                 CalendarConnectionWriteService connectionWriteService,
                                                 MicrosoftApiClient microsoftApiClient,
                                                 TokenCipher tokenCipher,
                                                 MeterRegistry meterRegistry,
                                                 @Value("${calendar.webhook.shared-secret:}") String webhookClientState,
                                                 @Value("${calendar.webhook.provider.microsoft.address:http://localhost:8080/integrations/calendar/webhooks/microsoft}") String webhookAddress,
                                                 @Value("${calendar.webhook.renewal.lead-time:PT24H}") Duration renewalLeadTime,
                                                 @Value("${calendar.webhook.provider.microsoft.ttl-seconds:7200}") long ttlSeconds) {
        this.connectionRepository = connectionRepository;
        this.connectionWriteService = connectionWriteService;
        this.microsoftApiClient = microsoftApiClient;
        this.tokenCipher = tokenCipher;
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
                String accessToken = refreshAccessToken(connection);
                renewOrCreate(accessToken, connection);
                meterRegistry.counter("webhook_renewal_attempts_total", "provider", "microsoft", "outcome", "success").increment();
            } catch (RuntimeException ex) {
                meterRegistry.counter("webhook_renewal_attempts_total", "provider", "microsoft", "outcome", "failure").increment();
                log.warn("microsoft_watch_renewal_failed connectionId={} userId={}", connection.getId(), connection.getUserId(), ex);
            }
        }
    }

    private void renewOrCreate(String accessToken, CalendarConnection connection) {
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        String subscriptionId = connection.getWebhookChannelId();
        MicrosoftApiClient.WebhookSubscription subscription;
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            subscription = microsoftApiClient.renewEventSubscription(accessToken, subscriptionId, expiresAt);
        } else {
            subscription = microsoftApiClient.createEventSubscription(accessToken, webhookAddress, webhookClientState, expiresAt);
        }
        connectionWriteService.updateWebhookChannel(
                connection.getId(),
                subscription.subscriptionId(),
                subscription.resourceId(),
                subscription.expiresAt(),
                "microsoft_webhook_renewal");
    }

    private String refreshAccessToken(CalendarConnection connection) {
        String refreshToken = tokenCipher.decrypt(connection.getRefreshTokenCiphertext());
        TokenRefreshResult refreshResult = microsoftApiClient.refreshAccessToken(refreshToken);
        connectionWriteService.markActive(
                connection.getId(),
                refreshResult.expiresAt(),
                connection.getLastSyncedAt(),
                "microsoft_webhook_renewal_token_refresh");
        return refreshResult.accessToken();
    }
}
