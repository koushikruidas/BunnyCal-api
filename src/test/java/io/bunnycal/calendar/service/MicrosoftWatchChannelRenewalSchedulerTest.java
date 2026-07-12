package io.bunnycal.calendar.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.MicrosoftApiClient;
import io.bunnycal.calendar.config.CalendarWebhookProperties;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MicrosoftWatchChannelRenewalSchedulerTest {

    @Mock private CalendarConnectionRepository connectionRepository;
    @Mock private CalendarConnectionWriteService connectionWriteService;
    @Mock private MicrosoftApiClient microsoftApiClient;
    @Mock private TokenRefresher tokenRefresher;

    private MicrosoftWatchChannelRenewalScheduler scheduler;

    @BeforeEach
    void setUp() {
        CalendarWebhookProperties webhookProperties = new CalendarWebhookProperties();
        webhookProperties.setEnabled(true);
        webhookProperties.setSharedSecret("test-secret");
        webhookProperties.getProvider().getMicrosoft().setEnabled(true);
        webhookProperties.getProvider().getMicrosoft()
                .setAddress("http://localhost:8080/integrations/calendar/webhooks/microsoft");

        scheduler = new MicrosoftWatchChannelRenewalScheduler(
                connectionRepository,
                connectionWriteService,
                microsoftApiClient,
                tokenRefresher,
                new SimpleMeterRegistry(),
                webhookProperties,
                Duration.ofHours(24),
                7200L);

        lenient().when(tokenRefresher.executeWithValidToken(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<String, Object> operation = invocation.getArgument(1);
            return operation.apply("access-token");
        });
    }

    /**
     * A Graph subscription that has lapsed is garbage-collected, and every PATCH against its id
     * answers 404 ResourceNotFound. Because renewOrCreate branches on "do we hold a subscription
     * id?", a dead id keeps selecting the renew path — so the connection retries the same doomed
     * call on every sweep and never recovers. Production reached failureCount=73 this way.
     *
     * <p>Forgetting the id is what lets the next sweep take the create branch and heal.
     */
    @Test
    void renewal_subscriptionGone_clearsChannelIdSoNextSweepRecreates() {
        CalendarConnection connection = connection("dead-subscription");
        when(connectionRepository.findActiveRequiringWatchRenewal(eq(CalendarProviderType.MICROSOFT), any()))
                .thenReturn(List.of(connection));
        when(microsoftApiClient.renewEventSubscription(eq("access-token"), eq("dead-subscription"), any()))
                .thenThrow(new CalendarClientException(404,
                        "{\"error\":{\"code\":\"ResourceNotFound\",\"message\":\"The object was not found.\"}}"));

        scheduler.renewExpiringChannels();

        // The dead id must be forgotten, so renewOrCreate takes the create branch next time.
        verify(connectionWriteService).updateWebhookChannel(
                eq(connection.getId()), isNull(), isNull(), isNull(), eq("microsoft_watch_subscription_gone"));
    }

    /**
     * A transient failure must NOT clear the id. The subscription is very likely still alive at
     * Graph; dropping our reference to it would orphan it and leak a subscription on every blip.
     */
    @Test
    void renewal_transientFailure_keepsChannelIdAndDoesNotLeakSubscription() {
        CalendarConnection connection = connection("live-subscription");
        when(connectionRepository.findActiveRequiringWatchRenewal(eq(CalendarProviderType.MICROSOFT), any()))
                .thenReturn(List.of(connection));
        // classify() maps a network error to 503, not 404.
        when(microsoftApiClient.renewEventSubscription(eq("access-token"), eq("live-subscription"), any()))
                .thenThrow(new CalendarClientException(503, "connection reset"));

        scheduler.renewExpiringChannels();

        verify(connectionWriteService, never()).updateWebhookChannel(
                any(), isNull(), isNull(), isNull(), eq("microsoft_watch_subscription_gone"));
    }

    /** A watchless connection creates a fresh subscription rather than renewing a missing one. */
    @Test
    void renewal_watchlessConnection_createsSubscription() {
        CalendarConnection connection = connection(null);
        when(connectionRepository.findActiveRequiringWatchRenewal(eq(CalendarProviderType.MICROSOFT), any()))
                .thenReturn(List.of(connection));
        when(microsoftApiClient.createEventSubscription(eq("access-token"), any(), eq("test-secret"), any()))
                .thenReturn(new MicrosoftApiClient.WebhookSubscription(
                        "new-subscription", "new-resource", Instant.now().plusSeconds(7200), "test-secret"));
        when(connectionWriteService.updateWebhookChannelIfActive(
                eq(connection.getId()), eq("new-subscription"), any(), any(), any(), eq("microsoft_webhook_renewal")))
                .thenReturn(connection);

        scheduler.renewExpiringChannels();

        verify(microsoftApiClient).createEventSubscription(eq("access-token"), any(), eq("test-secret"), any());
        verify(microsoftApiClient, never()).renewEventSubscription(any(), any(), any());
    }

    private static CalendarConnection connection(String subscriptionId) {
        CalendarConnection connection = new CalendarConnection();
        try {
            java.lang.reflect.Field id = CalendarConnection.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(connection, UUID.randomUUID());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        connection.setUserId(UUID.randomUUID());
        connection.setProvider(CalendarProviderType.MICROSOFT);
        connection.setStatus(CalendarConnectionStatus.ACTIVE);
        connection.setProviderUserId("ms-user");
        connection.setWebhookChannelId(subscriptionId);
        connection.setWebhookChannelExpiresAt(
                subscriptionId == null ? null : Instant.now().plusSeconds(600));
        return connection;
    }
}
