package io.bunnycal.calendar.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.GoogleApiClient;
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
        scheduler = new GoogleWatchChannelRenewalScheduler(
                connectionRepository,
                tokenRefresher,
                googleApiClient,
                connectionWriteService,
                slotCacheVersionService,
                new SimpleMeterRegistry(),
                "http://localhost:8080/integrations/calendar/webhooks/google",
                "test-secret",
                Duration.ofHours(24));

        lenient().when(tokenRefresher.executeWithValidToken(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<String, Object> operation = invocation.getArgument(1);
            return operation.apply("access-token");
        });
    }

    @Test
    void renewExpiringChannels_renewsActiveConnections() {
        CalendarConnection connection = connection(CalendarConnectionStatus.ACTIVE);
        when(connectionRepository.findByProviderAndWebhookChannelExpiresAtBefore(eq(CalendarProviderType.GOOGLE), any()))
                .thenReturn(List.of(connection));
        when(googleApiClient.watchEvents("access-token",
                "http://localhost:8080/integrations/calendar/webhooks/google",
                "test-secret"))
                .thenReturn(new GoogleApiClient.WatchChannel("new-channel", "new-resource", Instant.now().plusSeconds(7200)));

        scheduler.renewExpiringChannels();

        verify(googleApiClient).stopWatchChannel("access-token", "old-channel", "old-resource");
        verify(connectionWriteService).updateWebhookChannel(
                eq(connection.getId()),
                eq("new-channel"),
                eq("new-resource"),
                any(),
                eq("google_watch_renewal"));
        verify(slotCacheVersionService).bumpVersion(connection.getUserId());
    }

    @Test
    void renewExpiringChannels_skipsInactiveConnections() {
        CalendarConnection connection = connection(CalendarConnectionStatus.ERROR);
        when(connectionRepository.findByProviderAndWebhookChannelExpiresAtBefore(eq(CalendarProviderType.GOOGLE), any()))
                .thenReturn(List.of(connection));

        scheduler.renewExpiringChannels();

        verify(tokenRefresher, never()).executeWithValidToken(any(), any());
        verify(connectionWriteService, never()).updateWebhookChannel(any(), any(), any(), any(), any());
    }

    private static CalendarConnection connection(CalendarConnectionStatus status) {
        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(UUID.randomUUID());
        connection.setProvider(CalendarProviderType.GOOGLE);
        connection.setStatus(status);
        connection.setWebhookChannelId("old-channel");
        connection.setWebhookResourceId("old-resource");
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
