package com.daedalussystems.easySchedule.booking.outbox;

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

    @Override
    public void dispatch(OutboxEvent event) {
        log.info("outbox.dispatch id={} type={} aggregateType={} aggregateId={}",
                event.getId(), event.getEventType(),
                event.getAggregateType(), event.getAggregateId());
    }
}
