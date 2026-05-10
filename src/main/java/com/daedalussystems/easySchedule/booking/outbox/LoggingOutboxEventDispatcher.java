package com.daedalussystems.easySchedule.booking.outbox;

import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default dispatcher that logs the event and does nothing else.
 *
 * <p>Replace or extend with a real implementation once a downstream consumer
 * (notification service, calendar sync, etc.) is available.
 */
@Component
public class LoggingOutboxEventDispatcher implements OutboxEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxEventDispatcher.class);
    private static final String BOOKING_AGGREGATE = "Booking";

    private final CalendarSyncJobRepository calendarSyncJobRepository;
    private final String provider;

    public LoggingOutboxEventDispatcher(CalendarSyncJobRepository calendarSyncJobRepository,
                                        @Value("${sync.provider.default:google}") String provider) {
        this.calendarSyncJobRepository = calendarSyncJobRepository;
        this.provider = provider;
    }

    @Override
    public void dispatch(OutboxEvent event) {
        if (isBookingSyncCandidate(event)) {
            String desiredAction = mapDesiredAction(event.getEventType());
            if (desiredAction != null) {
                calendarSyncJobRepository.upsertPendingJob(
                        UUID.randomUUID(),
                        "BOOKING",
                        event.getAggregateId(),
                        provider,
                        desiredAction,
                        null
                );
                log.info("outbox.sync_job_created id={} bookingId={} provider={} action={}",
                        event.getId(), event.getAggregateId(), provider, desiredAction);
                return;
            }
        }

        log.info("outbox.dispatch id={} type={} aggregateType={} aggregateId={}",
                event.getId(), event.getEventType(),
                event.getAggregateType(), event.getAggregateId());
    }

    private static boolean isBookingSyncCandidate(OutboxEvent event) {
        return event != null
                && BOOKING_AGGREGATE.equals(event.getAggregateType())
                && event.getAggregateId() != null;
    }

    private static String mapDesiredAction(String eventType) {
        if ("BOOKING_CONFIRMED".equals(eventType)) {
            return "CREATE";
        }
        if ("BOOKING_UPDATED".equals(eventType)) {
            return "UPDATE";
        }
        if ("BOOKING_CANCELLED".equals(eventType)) {
            return "DELETE";
        }
        return null;
    }
}
