package io.bunnycal.calendar.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.calendar.domain.CalendarWebhookReplayFixture;
import io.bunnycal.calendar.replay.WebhookDeliveryMetadata;
import io.bunnycal.calendar.repository.CalendarWebhookReplayFixtureRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarWebhookReplayCaptureServiceTest {

    @Mock
    private CalendarWebhookReplayFixtureRepository repository;

    private CalendarWebhookReplayCaptureService service;

    @BeforeEach
    void setUp() {
        service = new CalendarWebhookReplayCaptureService(repository, new SimpleMeterRegistry(), true);
    }

    @Test
    void capture_persistsFixtureIncludingDuplicateAndRecurringHint() {
        CalendarWebhookReplayFixture saved = new CalendarWebhookReplayFixture();
        when(repository.save(any())).thenReturn(saved);

        service.capture(
                "google",
                UUID.randomUUID(),
                "evt-1",
                "{\"recurringEventId\":\"r1\",\"status\":\"confirmed\"}",
                "delivery-key",
                "payload-hash",
                true,
                new WebhookDeliveryMetadata(Instant.parse("2026-05-14T10:00:00Z"), "etag", 5L, "delivery-1", SyncSourceAttribution.WEBHOOK)
        );

        verify(repository).save(any(CalendarWebhookReplayFixture.class));
    }
}
