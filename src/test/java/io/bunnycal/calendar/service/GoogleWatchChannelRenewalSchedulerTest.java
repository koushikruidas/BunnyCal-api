package io.bunnycal.calendar.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.GoogleApiClient;
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
class GoogleWatchChannelRenewalSchedulerTest {

    @Mock
    private CalendarConnectionRepository connectionRepository;
    @Mock
    private TokenRefresher tokenRefresher;
    @Mock
    private GoogleApiClient googleApiClient;
    @Mock
    private CalendarConnectionWriteService connectionWriteService;
    @Mock
    private SlotCacheVersionService slotCacheVersionService;

    private GoogleWatchChannelRenewalScheduler scheduler;

    @BeforeEach
    void setUp() {
        CalendarWebhookProperties webhookProperties = new CalendarWebhookProperties();
        webhookProperties.setEnabled(true);
        webhookProperties.setSharedSecret("test-secret");
        webhookProperties.getProvider().getGoogle().setEnabled(true);
        webhookProperties.getProvider().getGoogle().setAddress("http://localhost:8080/integrations/calendar/webhooks/google");
        scheduler = new GoogleWatchChannelRenewalScheduler(
                connectionRepository,
                tokenRefresher,
                googleApiClient,
                connectionWriteService,
                slotCacheVersionService,
                new SimpleMeterRegistry(),
                webhookProperties,
                Duration.ofHours(24));

        lenient().when(tokenRefresher.executeWithValidToken(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<String, Object> operation = invocation.getArgument(1);
            return operation.apply("access-token");
        });
    }

    @Test
    void renewExpiringChannels_createsNewChannelThenStopsOld() {
        // Phase 4 R2 + R9 atomicity: order must be create-new → write-DB → stop-old.
        CalendarConnection connection = connection(CalendarConnectionStatus.ACTIVE, "old-channel", "old-resource");
        when(connectionRepository.findActiveRequiringWatchRenewal(eq(CalendarProviderType.GOOGLE), any()))
                .thenReturn(List.of(connection));
        when(googleApiClient.watchEvents("access-token",
                "http://localhost:8080/integrations/calendar/webhooks/google",
                "test-secret"))
                .thenReturn(new GoogleApiClient.WatchChannel("new-channel", "new-resource", Instant.now().plusSeconds(7200)));
        // updateWebhookChannelIfActive returns a non-null result → status guard passed.
        when(connectionWriteService.updateWebhookChannelIfActive(
                eq(connection.getId()), eq("new-channel"), eq("new-resource"), any(), any(), eq("google_watch_renewal")))
                .thenReturn(connection);

        scheduler.renewExpiringChannels();

        verify(connectionWriteService).updateWebhookChannelIfActive(
                eq(connection.getId()), eq("new-channel"), eq("new-resource"), any(), any(), eq("google_watch_renewal"));
        verify(googleApiClient).stopWatchChannel("access-token", "old-channel", "old-resource");
        verify(slotCacheVersionService).bumpVersion(connection.getUserId());
    }

    @Test
    void renewExpiringChannels_recoversWatchlessConnection() {
        // Phase 4 R1: ACTIVE row with NULL webhook_channel_expires_at (initial watchEvents
        // silently failed at OAuth callback) is now picked up and healed. No old channel
        // to stop.
        CalendarConnection watchless = connection(CalendarConnectionStatus.ACTIVE, null, null);
        watchless.setWebhookChannelExpiresAt(null);
        when(connectionRepository.findActiveRequiringWatchRenewal(eq(CalendarProviderType.GOOGLE), any()))
                .thenReturn(List.of(watchless));
        when(googleApiClient.watchEvents(any(), any(), any()))
                .thenReturn(new GoogleApiClient.WatchChannel("fresh-channel", "fresh-resource", Instant.now().plusSeconds(7200)));
        when(connectionWriteService.updateWebhookChannelIfActive(
                eq(watchless.getId()), eq("fresh-channel"), eq("fresh-resource"), any(), any(), any()))
                .thenReturn(watchless);

        scheduler.renewExpiringChannels();

        verify(googleApiClient).watchEvents(any(), any(), any());
        verify(connectionWriteService).updateWebhookChannelIfActive(
                eq(watchless.getId()), eq("fresh-channel"), eq("fresh-resource"), any(), any(), any());
        // No old channel to stop.
        verify(googleApiClient, never()).stopWatchChannel(any(), any(), any());
    }

    @Test
    void renewExpiringChannels_statusGuardSkipped_stopsNewlyCreatedChannel() {
        // Phase 4 R10: disconnect raced renewal between watchEvents and the DB write.
        // updateWebhookChannelIfActive returns null → must stop the just-created channel.
        CalendarConnection connection = connection(CalendarConnectionStatus.ACTIVE, "old-channel", "old-resource");
        when(connectionRepository.findActiveRequiringWatchRenewal(eq(CalendarProviderType.GOOGLE), any()))
                .thenReturn(List.of(connection));
        when(googleApiClient.watchEvents(any(), any(), any()))
                .thenReturn(new GoogleApiClient.WatchChannel("leaked-channel", "leaked-resource", Instant.now().plusSeconds(7200)));
        when(connectionWriteService.updateWebhookChannelIfActive(any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        scheduler.renewExpiringChannels();

        // The leaked-channel must be stopped. The old-channel must NOT be stopped because
        // the row is no longer ACTIVE and disconnect itself is responsible for that.
        verify(googleApiClient).stopWatchChannel("access-token", "leaked-channel", "leaked-resource");
        verify(googleApiClient, never()).stopWatchChannel("access-token", "old-channel", "old-resource");
    }

    @Test
    void renewExpiringChannels_failureRecordsWatchRenewalFailure() {
        // Phase 4 R8: every renewal exception records a per-connection failure.
        CalendarConnection connection = connection(CalendarConnectionStatus.ACTIVE, "old-channel", "old-resource");
        when(connectionRepository.findActiveRequiringWatchRenewal(eq(CalendarProviderType.GOOGLE), any()))
                .thenReturn(List.of(connection));
        when(googleApiClient.watchEvents(any(), any(), any()))
                .thenThrow(new RuntimeException("google rate limit"));

        scheduler.renewExpiringChannels();

        verify(connectionWriteService).recordWatchRenewalFailure(
                eq(connection.getId()), any(), eq("google_watch_renewal_failure"));
        verify(connectionWriteService, never()).updateWebhookChannelIfActive(
                any(), any(), any(), any(), any(), any());
    }

    private static CalendarConnection connection(CalendarConnectionStatus status,
                                                 String channelId,
                                                 String resourceId) {
        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(UUID.randomUUID());
        connection.setProvider(CalendarProviderType.GOOGLE);
        connection.setStatus(status);
        connection.setWebhookChannelId(channelId);
        connection.setWebhookResourceId(resourceId);
        connection.setWebhookChannelExpiresAt(Instant.now().plusSeconds(1800));
        try {
            java.lang.reflect.Field idField = CalendarConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(connection, UUID.randomUUID());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return connection;
    }
}
