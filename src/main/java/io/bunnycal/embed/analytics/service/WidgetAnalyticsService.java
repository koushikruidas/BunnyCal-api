package io.bunnycal.embed.analytics.service;

import io.bunnycal.embed.analytics.domain.WidgetEvent;
import io.bunnycal.embed.analytics.domain.WidgetSession;
import io.bunnycal.embed.analytics.repository.WidgetEventRepository;
import io.bunnycal.embed.analytics.repository.WidgetSessionRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WidgetAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(WidgetAnalyticsService.class);

    private static final List<String> STAGE_ORDER = List.of(
            "WIDGET_LOADED", "DATE_SELECTED", "TIME_SELECTED",
            "FORM_STARTED", "FORM_SUBMITTED", "BOOKING_CONFIRMED");

    private static final java.util.Set<String> ALLOWED_EVENTS =
            new java.util.HashSet<>(STAGE_ORDER);

    private final WidgetSessionRepository sessionRepository;
    private final WidgetEventRepository eventRepository;

    public WidgetAnalyticsService(WidgetSessionRepository sessionRepository,
                                  WidgetEventRepository eventRepository) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
    }

    @Async("analyticsExecutor")
    @Transactional
    public void startSession(UUID sessionId, UUID experienceId, UUID anonymousId,
                             String utmSource, String utmMedium, String utmCampaign, String referrer) {
        try {
            WidgetSession session = WidgetSession.builder()
                    .id(sessionId)
                    .bookingExperienceId(experienceId)
                    .anonymousId(anonymousId)
                    .utmSource(utmSource)
                    .utmMedium(utmMedium)
                    .utmCampaign(utmCampaign)
                    .referrer(referrer)
                    .build();
            sessionRepository.save(session);
        } catch (Exception ex) {
            log.warn("analytics_session_start_failed experienceId={} anonymousId={}", experienceId, anonymousId, ex);
        }
    }

    @Async("analyticsExecutor")
    @Transactional
    public void recordEvent(UUID sessionId, String eventName) {
        try {
            if (!ALLOWED_EVENTS.contains(eventName)) {
                log.warn("analytics_unknown_event sessionId={} eventName={}", sessionId, eventName);
                return;
            }

            WidgetSession session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                log.warn("analytics_session_not_found sessionId={}", sessionId);
                return;
            }

            int incoming = STAGE_ORDER.indexOf(eventName);
            int current = STAGE_ORDER.indexOf(session.getCurrentStage());
            if (incoming > current) {
                session.setCurrentStage(eventName);
                sessionRepository.save(session);
            }

            eventRepository.save(WidgetEvent.builder()
                    .sessionId(sessionId)
                    .eventName(eventName)
                    .build());

        } catch (Exception ex) {
            log.warn("analytics_record_event_failed sessionId={} eventName={}", sessionId, eventName, ex);
        }
    }
}
