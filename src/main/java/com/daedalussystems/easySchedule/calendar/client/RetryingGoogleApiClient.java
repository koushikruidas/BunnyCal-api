package com.daedalussystems.easySchedule.calendar.client;

import com.daedalussystems.easySchedule.calendar.provider.CreateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
import java.time.Duration;
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
    public String createEvent(String accessToken, CreateEventRequest request) {
        return withRetry(() -> delegate.createEvent(accessToken, request));
    }

    @Override
    public String updateEvent(String accessToken, UpdateEventRequest request) {
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
    public TokenRefreshResult refreshAccessToken(String refreshToken) {
        return delegate.refreshAccessToken(refreshToken);
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
