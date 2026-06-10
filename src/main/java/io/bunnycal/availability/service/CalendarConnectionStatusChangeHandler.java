package io.bunnycal.availability.service;

import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reacts to calendar connection status changes and enforces Collective event type
 * publish-state readiness for any affected published events.
 *
 * <p>Call {@link #onStatusChanged} whenever a {@code CalendarConnection.status} transitions.
 * Structural failures (REVOKED, DISCONNECTED) will trigger auto-unpublish.
 * Transient failures (FAILED, ERROR) will trigger a degraded warning (rate-limited).
 */
@Component
public class CalendarConnectionStatusChangeHandler {

    private static final Logger log = LoggerFactory.getLogger(CalendarConnectionStatusChangeHandler.class);

    private final PublishReadinessService publishReadinessService;

    public CalendarConnectionStatusChangeHandler(PublishReadinessService publishReadinessService) {
        this.publishReadinessService = publishReadinessService;
    }

    /**
     * Called after a calendar connection status has changed for a given user.
     * Triggers readiness re-evaluation for all published COLLECTIVE events that
     * include this user as a participant.
     */
    @Transactional
    public void onStatusChanged(UUID userId, CalendarConnectionStatus newStatus) {
        log.debug("calendar_status_changed userId={} newStatus={}", userId, newStatus);
        publishReadinessService.enforceForParticipant(userId);
    }
}
