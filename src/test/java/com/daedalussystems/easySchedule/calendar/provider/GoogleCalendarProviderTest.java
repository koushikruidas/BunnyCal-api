package com.daedalussystems.easySchedule.calendar.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.auth.TokenRefresher;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarProviderTest {

    @Mock
    private GoogleApiClient googleApiClient;

    @Mock
    private TokenRefresher tokenRefresher;

    private GoogleCalendarProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GoogleCalendarProvider(googleApiClient, tokenRefresher);
    }

    @Test
    void createEvent_usesTokenRefresherAndGoogleClient() {
        UUID connectionId = UUID.randomUUID();
        CreateEventRequest request = new CreateEventRequest(
                connectionId,
                "title",
                "desc",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                "idem-1"
        );

        when(tokenRefresher.executeWithValidToken(any(), any())).thenAnswer(invocation -> {
            var fn = invocation.<java.util.function.Function<String, String>>getArgument(1);
            return fn.apply("token-1");
        });
        when(googleApiClient.createEvent("token-1", request)).thenReturn("evt-123");

        CreateEventResponse response = provider.createEvent(request);

        assertEquals("evt-123", response.externalEventId());
        verify(googleApiClient).createEvent("token-1", request);
    }
}
