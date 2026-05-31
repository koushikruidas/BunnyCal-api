package io.bunnycal.calendar.repository;

import io.bunnycal.calendar.domain.CalendarWebhookReplayFixture;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarWebhookReplayFixtureRepository extends JpaRepository<CalendarWebhookReplayFixture, UUID> {
    List<CalendarWebhookReplayFixture> findByConnectionIdOrderByArrivalIndexAsc(UUID connectionId);
    List<CalendarWebhookReplayFixture> findByProviderAndCapturedAtBetweenOrderByArrivalIndexAsc(
            String provider, Instant fromInclusive, Instant toExclusive);
}
