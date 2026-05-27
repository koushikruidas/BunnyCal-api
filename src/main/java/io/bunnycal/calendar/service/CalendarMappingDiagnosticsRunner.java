package io.bunnycal.calendar.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeOrchestrationJsonCodec;
import io.bunnycal.availability.service.EventTypeOrchestrationNormalizer.AvailabilityBinding;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-shot diagnostic scan that walks every event_type at boot, deserializes the stored
 * availability bindings, and warns whenever a binding's externalCalendarId equals its
 * connectionId. These rows are legacy corruption from when the frontend used connectionId
 * as the provider calendar identifier; logging them here surfaces the affected rows up
 * front so operators can target a targeted backfill rather than waiting for read-path
 * traffic to flush them out one user at a time.
 */
@Component
public class CalendarMappingDiagnosticsRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CalendarMappingDiagnosticsRunner.class);

    private final EventTypeRepository eventTypeRepository;
    private final EventTypeOrchestrationJsonCodec orchestrationJsonCodec;

    public CalendarMappingDiagnosticsRunner(EventTypeRepository eventTypeRepository,
                                            EventTypeOrchestrationJsonCodec orchestrationJsonCodec) {
        this.eventTypeRepository = eventTypeRepository;
        this.orchestrationJsonCodec = orchestrationJsonCodec;
    }

    @Override
    @Transactional(readOnly = true)
    public void run(org.springframework.boot.ApplicationArguments args) {
        int corruptCount = 0;
        int scanned = 0;
        for (EventType eventType : eventTypeRepository.findAll()) {
            scanned++;
            String raw = eventType.getAvailabilityCalendarsJson();
            if (raw == null || raw.isBlank()) continue;
            List<AvailabilityBinding> bindings = orchestrationJsonCodec.deserializeAvailabilityBindings(raw);
            for (AvailabilityBinding binding : bindings) {
                if (binding == null || binding.connectionId() == null || binding.externalCalendarId() == null) {
                    continue;
                }
                if (binding.connectionId().toString().equals(binding.externalCalendarId())) {
                    corruptCount++;
                    log.warn("invalid_calendar_mapping connectionId={} externalCalendarId={} reason=connection_id_used_as_calendar_id eventTypeId={} userId={} stage=startup_scan",
                            binding.connectionId(),
                            binding.externalCalendarId(),
                            eventType.getId(),
                            eventType.getUserId());
                }
            }
        }
        log.info("calendar_mapping_diagnostics_startup_scan_complete scannedEventTypes={} corruptBindings={}",
                scanned, corruptCount);
    }
}
