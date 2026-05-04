package com.daedalussystems.easySchedule.calendar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.provider.CreateEventRequest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetryingGoogleApiClientTest {

    @Mock
    private GoogleApiClient delegate;

    @Test
    void retriesOn429ThenSucceeds() {
        RetryingGoogleApiClient client = new RetryingGoogleApiClient(delegate);
        CreateEventRequest req = new CreateEventRequest(UUID.randomUUID(), "t", "d", Instant.now(), Instant.now(), "id1");

        AtomicInteger calls = new AtomicInteger();
        when(delegate.createEvent("tok", req)).thenAnswer(inv -> {
            if (calls.incrementAndGet() < 3) {
                throw new CalendarClientException(429, "rate limited");
            }
            return "evt-1";
        });

        assertEquals("evt-1", client.createEvent("tok", req));
        verify(delegate, times(3)).createEvent("tok", req);
    }

    @Test
    void doesNotRetryNonRetryable4xx() {
        RetryingGoogleApiClient client = new RetryingGoogleApiClient(delegate);
        CreateEventRequest req = new CreateEventRequest(UUID.randomUUID(), "t", "d", Instant.now(), Instant.now(), "id1");

        when(delegate.createEvent("tok", req)).thenThrow(new CalendarClientException(400, "bad request"));

        assertThrows(CalendarClientException.class, () -> client.createEvent("tok", req));
        verify(delegate, times(1)).createEvent("tok", req);
    }
}
