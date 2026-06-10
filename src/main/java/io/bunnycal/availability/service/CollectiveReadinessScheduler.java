package io.bunnycal.availability.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodic job that re-evaluates readiness for all published COLLECTIVE event types.
 *
 * <p>This covers participant deactivation, which has no synchronous hook in the current
 * architecture — user status changes don't fire an in-process event. The scheduler
 * detects deactivated participants within the configured interval (default: 10 minutes).
 *
 * <p>TODO (future): replace the polling approach with an event-driven hook on user status
 * changes. When a user is deactivated, publish a domain event that triggers readiness
 * re-evaluation for all their COLLECTIVE event types immediately.
 */
@Component
public class CollectiveReadinessScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectiveReadinessScheduler.class);

    private final EventTypeRepository eventTypeRepository;
    private final PublishReadinessService publishReadinessService;

    public CollectiveReadinessScheduler(
            EventTypeRepository eventTypeRepository,
            PublishReadinessService publishReadinessService) {
        this.eventTypeRepository = eventTypeRepository;
        this.publishReadinessService = publishReadinessService;
    }

    @Scheduled(fixedDelayString = "${collective.readiness.check-interval-ms:600000}")
    @Transactional
    public void checkAllPublishedCollective() {
        List<EventType> candidates = eventTypeRepository.findAllPublishedCollective();
        if (candidates.isEmpty()) return;
        log.debug("collective_readiness_check_start count={}", candidates.size());
        int autoUnpublished = 0;
        for (EventType et : candidates) {
            PublishReadinessService.CollectiveReadinessSummary before =
                    publishReadinessService.evaluate(et);
            if (!before.publishable() && !before.degraded()) {
                publishReadinessService.applyAndEnforce(et);
                autoUnpublished++;
            }
        }
        if (autoUnpublished > 0) {
            log.info("collective_readiness_check_complete total={} autoUnpublished={}",
                    candidates.size(), autoUnpublished);
        }
    }
}
