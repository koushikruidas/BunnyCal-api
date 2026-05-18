package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.calendar.auth.TokenRefresher;
import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
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
    private final String googleWebhookAddress;
    private final String googleWebhookToken;
    private final Duration renewalLeadTime;

    public GoogleWatchChannelRenewalScheduler(
            CalendarConnectionRepository connectionRepository,
            TokenRefresher tokenRefresher,
            GoogleApiClient googleApiClient,
            CalendarConnectionWriteService connectionWriteService,
            SlotCacheVersionService slotCacheVersionService,
            MeterRegistry meterRegistry,
            @Value("${calendar.webhook.provider.google.address:http://localhost:8080/integrations/calendar/webhooks/google}") String googleWebhookAddress,
            @Value("${calendar.webhook.shared-secret:}") String googleWebhookToken,
            @Value("${calendar.webhook.renewal.lead-time:PT24H}") Duration renewalLeadTime) {
        this.connectionRepository = connectionRepository;
        this.tokenRefresher = tokenRefresher;
        this.googleApiClient = googleApiClient;
        this.connectionWriteService = connectionWriteService;
        this.slotCacheVersionService = slotCacheVersionService;
        this.meterRegistry = meterRegistry;
        this.googleWebhookAddress = googleWebhookAddress;
        this.googleWebhookToken = googleWebhookToken;
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
        if (googleWebhookAddress == null || googleWebhookAddress.isBlank()
                || googleWebhookToken == null || googleWebhookToken.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        Instant renewalThreshold = now.plus(renewalLeadTime);
        List<CalendarConnection> candidates = connectionRepository.findByProviderAndWebhookChannelExpiresAtBefore(
                GOOGLE_PROVIDER,
                renewalThreshold);

        for (CalendarConnection connection : candidates) {
            if (connection.getStatus() != CalendarConnectionStatus.ACTIVE) {
                incrementCounter("webhook_renewal_skipped_inactive_total", "status", connection.getStatus().name());
                continue;
            }
            incrementCounter("webhook_renewal_attempts_total", "outcome", "attempt");
            try {
                renewConnection(connection);
                incrementCounter("webhook_renewal_attempts_total", "outcome", "success");
            } catch (RuntimeException ex) {
                String errorCode = resolveErrorCode(ex);
                incrementCounter("webhook_renewal_attempts_total", "outcome", "failure");
                incrementCounter("webhook_renewal_failures_total", "errorCode", errorCode);
                log.warn("google_watch_renewal_failed connectionId={} userId={} errorCode={}",
                        connection.getId(), connection.getUserId(), errorCode, ex);
            }
        }
    }

    private void renewConnection(CalendarConnection connection) {
        String channelId = connection.getWebhookChannelId();
        String resourceId = connection.getWebhookResourceId();
        if (channelId != null && !channelId.isBlank() && resourceId != null && !resourceId.isBlank()) {
            try {
                tokenRefresher.executeWithValidToken(connection.getId(), accessToken -> {
                    googleApiClient.stopWatchChannel(accessToken, channelId, resourceId);
                    return null;
                });
            } catch (RuntimeException ex) {
                log.warn("google_watch_stop_failed connectionId={} channelId={}", connection.getId(), channelId, ex);
            }
        }

        GoogleApiClient.WatchChannel renewed = tokenRefresher.executeWithValidToken(connection.getId(), accessToken ->
                googleApiClient.watchEvents(accessToken, googleWebhookAddress, googleWebhookToken));

        connectionWriteService.updateWebhookChannel(
                connection.getId(),
                renewed.channelId(),
                renewed.resourceId(),
                renewed.expiration(),
                "google_watch_renewal");
        slotCacheVersionService.bumpVersion(connection.getUserId());
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
