package com.daedalussystems.easySchedule.calendar.client;

import com.daedalussystems.easySchedule.calendar.provider.CreateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class RetryingGoogleApiClient implements GoogleApiClient {
    private static final int MAX_RETRIES = 3;

    private final GoogleApiClient delegate;

    public RetryingGoogleApiClient(@Qualifier("rawGoogleApiClient") GoogleApiClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public GoogleEventDetails createEvent(String accessToken, CreateEventRequest request) {
        return withRetry(() -> delegate.createEvent(accessToken, request));
    }

    @Override
    public GoogleEventDetails updateEvent(String accessToken, UpdateEventRequest request) {
        return withRetry(() -> delegate.updateEvent(accessToken, request));
    }

    @Override
    public void deleteEvent(String accessToken, String externalEventId) {
        withRetry(() -> {
            delegate.deleteEvent(accessToken, externalEventId);
            return null;
        });
    }

    @Override
    public boolean eventExists(String accessToken, String externalEventId) {
        return withRetry(() -> delegate.eventExists(accessToken, externalEventId));
    }

    @Override
    public TokenRefreshResult refreshAccessToken(String refreshToken) {
        return delegate.refreshAccessToken(refreshToken);
    }

    @Override
    public OAuthTokenExchangeResult exchangeCodeForToken(String code,
                                                         String redirectUri,
                                                         String clientId,
                                                         String clientSecret) {
        return delegate.exchangeCodeForToken(code, redirectUri, clientId, clientSecret);
    }

    @Override
    public String fetchProviderUserId(String accessToken) {
        return withRetry(() -> delegate.fetchProviderUserId(accessToken));
    }

    @Override
    public List<BusyInterval> fetchBusyIntervals(String accessToken, Instant start, Instant end) {
        return withRetry(() -> delegate.fetchBusyIntervals(accessToken, start, end));
    }

    @Override
    public SyncWindow listEventsFull(String accessToken) {
        return withRetry(() -> delegate.listEventsFull(accessToken));
    }

    @Override
    public SyncWindow listEventsIncremental(String accessToken, String syncCursor) {
        return withRetry(() -> delegate.listEventsIncremental(accessToken, syncCursor));
    }

    @Override
    public WatchChannel watchEvents(String accessToken, String webhookUrl, String channelToken) {
        return withRetry(() -> delegate.watchEvents(accessToken, webhookUrl, channelToken));
    }

    @Override
    public void stopWatchChannel(String accessToken, String channelId, String resourceId) {
        withRetry(() -> {
            delegate.stopWatchChannel(accessToken, channelId, resourceId);
            return null;
        });
    }

    @Override
    public void revokeToken(String token) {
        withRetry(() -> {
            delegate.revokeToken(token);
            return null;
        });
    }

    private interface Call<T> { T invoke(); }

    private <T> T withRetry(Call<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.invoke();
            } catch (CalendarClientException ex) {
                if (!ex.isRetryable() || attempt >= MAX_RETRIES) {
                    throw ex;
                }
                sleepBackoff(++attempt);
            }
        }
    }

    private static void sleepBackoff(int attempt) {
        long base = Math.min(1000L * (1L << Math.max(0, attempt - 1)), 8_000L);
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, base / 2));
        try {
            Thread.sleep(Duration.ofMillis(base / 2 + jitter).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during backoff", e);
        }
    }
}
