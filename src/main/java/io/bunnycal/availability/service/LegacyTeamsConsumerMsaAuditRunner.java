package io.bunnycal.availability.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.MicrosoftAccountClassifier;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup audit for pre-existing invalid configs that predate validation guards.
 */
@Component
public class LegacyTeamsConsumerMsaAuditRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyTeamsConsumerMsaAuditRunner.class);

    private final EventTypeRepository eventTypeRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final MeterRegistry meterRegistry;

    public LegacyTeamsConsumerMsaAuditRunner(EventTypeRepository eventTypeRepository,
                                             CalendarConnectionRepository calendarConnectionRepository,
                                             MeterRegistry meterRegistry) {
        this.eventTypeRepository = eventTypeRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.meterRegistry = meterRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void auditLegacyInvalidTeamsConfigs() {
        List<EventType> invalid = eventTypeRepository.findAll().stream()
                .filter(et -> et.getProjectionProvider() == CalendarProviderType.MICROSOFT)
                .filter(et -> et.getConferencingProvider() == ConferencingProviderType.MICROSOFT_TEAMS)
                .filter(et -> et.getProjectionConnectionId() != null)
                .filter(et -> {
                    CalendarConnection connection = calendarConnectionRepository
                            .findById(et.getProjectionConnectionId())
                            .orElse(null);
                    return MicrosoftAccountClassifier.isConsumerMsa(connection);
                })
                .toList();

        meterRegistry.counter("legacy_invalid_teams_consumer_msa_event_types_total")
                .increment(invalid.size());
        if (invalid.isEmpty()) {
            return;
        }
        log.warn("legacy_invalid_teams_consumer_msa_configs_detected count={}", invalid.size());
        for (EventType eventType : invalid) {
            log.warn("legacy_invalid_teams_consumer_msa_config eventTypeId={} userId={} slug={} projectionConnectionId={}",
                    eventType.getId(),
                    eventType.getUserId(),
                    eventType.getSlug(),
                    eventType.getProjectionConnectionId());
        }
    }
}
