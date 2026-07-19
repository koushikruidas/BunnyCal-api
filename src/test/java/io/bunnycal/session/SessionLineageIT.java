package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.RecurrenceFrequency;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.dto.ReservationWindowRequest;
import io.bunnycal.availability.service.GroupEventReservationWindowService;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionService;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session lineage: where an occurrence came from, recorded once and never rewritten.
 *
 * <p>{@code reservationWindowId} and {@code scheduledOccurrenceStart} are write-once by
 * contract (mapped {@code updatable = false}). They answer "which rule created this, and
 * where did it originally sit?" — questions that become unanswerable if a reschedule is
 * allowed to overwrite them, since {@code startTime} has moved by then.
 */
class SessionLineageIT extends AbstractSessionIT {

    @Autowired private SessionService sessionService;
    @Autowired private GroupEventReservationWindowService windowService;

    /** Next Monday at 09:00 UTC — matches the recurring window defined below. */
    private Instant nextMondayAt9() {
        LocalDate monday = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return monday.atTime(LocalTime.of(9, 0)).toInstant(ZoneOffset.UTC);
    }

    private User hostWithMondayAvailability() {
        User host = createHost(); // timezone UTC
        jdbc.update("""
                INSERT INTO availability_rules
                    (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                VALUES (?, ?, 'MONDAY', '09:00', '17:00', NOW(), NOW())
                """, UUID.randomUUID(), host.getId());
        return host;
    }

    private UUID createMondayWindow(User host, EventType group) {
        return windowService.replaceWindows(host.getId(), group.getId(),
                List.of(new ReservationWindowRequest(
                        null, ScheduleType.RECURRING,
                        LocalTime.of(9, 0), LocalTime.of(17, 0),
                        null, DayOfWeek.MONDAY, RecurrenceFrequency.WEEKLY,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        RecurrenceEndMode.NONE, null, null)))
                .get(0).id();
    }

    private Map<String, Object> lineageOf(UUID sessionId) {
        return jdbc.queryForMap("""
                SELECT reservation_window_id, scheduled_occurrence_start,
                       start_time, detached_at, detached_reason
                FROM event_sessions WHERE id = ?
                """, sessionId);
    }

    @Test
    void materializedSession_recordsTheWindowThatGeneratedIt() {
        User host = hostWithMondayAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        UUID windowId = createMondayWindow(host, group);

        Instant start = nextMondayAt9();
        JoinSessionResult result = sessionService.joinSession(
                host.getId(), group.getId(), start, start.plus(1, ChronoUnit.HOURS),
                10, "guest@test.com", "Guest", Duration.ofMinutes(15));

        Map<String, Object> lineage = lineageOf(result.sessionId());
        assertThat(lineage.get("reservation_window_id")).isEqualTo(windowId);
        assertThat(((Timestamp) lineage.get("scheduled_occurrence_start")).toInstant())
                .isEqualTo(start);
        // Still following its rule.
        assertThat(lineage.get("detached_at")).isNull();
    }

    @Test
    void repeatedReschedules_neverRewriteLineage() {
        User host = hostWithMondayAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        UUID windowId = createMondayWindow(host, group);

        Instant originalStart = nextMondayAt9();
        JoinSessionResult result = sessionService.joinSession(
                host.getId(), group.getId(), originalStart, originalStart.plus(1, ChronoUnit.HOURS),
                10, "guest@test.com", "Guest", Duration.ofMinutes(15));
        UUID sessionId = result.sessionId();

        // Move it three times, well away from where the rule placed it.
        sessionService.rescheduleSession(sessionId, host.getId(), originalStart.plus(1, ChronoUnit.DAYS));
        sessionService.rescheduleSession(sessionId, host.getId(), originalStart.plus(3, ChronoUnit.DAYS));
        sessionService.rescheduleSession(sessionId, host.getId(), originalStart.plus(9, ChronoUnit.DAYS));

        Map<String, Object> lineage = lineageOf(sessionId);
        // start_time moved...
        assertThat(((Timestamp) lineage.get("start_time")).toInstant())
                .isEqualTo(originalStart.plus(9, ChronoUnit.DAYS));
        // ...lineage did not. This is the regression guard for the write-once rule:
        // a future move handler that "helpfully" updates these breaks here.
        assertThat(lineage.get("reservation_window_id")).isEqualTo(windowId);
        assertThat(((Timestamp) lineage.get("scheduled_occurrence_start")).toInstant())
                .isEqualTo(originalStart);
    }

    @Test
    void hostReschedule_marksTheSessionDetachedWithAReason() {
        User host = hostWithMondayAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        createMondayWindow(host, group);

        Instant start = nextMondayAt9();
        JoinSessionResult result = sessionService.joinSession(
                host.getId(), group.getId(), start, start.plus(1, ChronoUnit.HOURS),
                10, "guest@test.com", "Guest", Duration.ofMinutes(15));

        sessionService.rescheduleSession(result.sessionId(), host.getId(), start.plus(2, ChronoUnit.DAYS));

        Map<String, Object> lineage = lineageOf(result.sessionId());
        assertThat(lineage.get("detached_at")).isNotNull();
        assertThat(lineage.get("detached_reason")).isEqualTo("HOST_RESCHEDULED");
    }

    @Test
    void detachReason_survivesFurtherReschedules() {
        User host = hostWithMondayAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        createMondayWindow(host, group);

        Instant start = nextMondayAt9();
        JoinSessionResult result = sessionService.joinSession(
                host.getId(), group.getId(), start, start.plus(1, ChronoUnit.HOURS),
                10, "guest@test.com", "Guest", Duration.ofMinutes(15));

        sessionService.rescheduleSession(result.sessionId(), host.getId(), start.plus(2, ChronoUnit.DAYS));
        Instant firstDetach = ((Timestamp) lineageOf(result.sessionId()).get("detached_at")).toInstant();

        sessionService.rescheduleSession(result.sessionId(), host.getId(), start.plus(4, ChronoUnit.DAYS));

        // The session detached once; later moves don't restamp when it happened.
        assertThat(((Timestamp) lineageOf(result.sessionId()).get("detached_at")).toInstant())
                .isEqualTo(firstDetach);
    }

    @Test
    void sessionOutsideAnyWindow_bookableWithNullLineage() {
        User host = hostWithMondayAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        createMondayWindow(host, group);

        // A Saturday slot no rule covers — the shape a future host-created ad-hoc
        // session takes. Lineage is metadata, never a booking precondition.
        Instant saturday = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
                .atTime(LocalTime.of(14, 0)).toInstant(ZoneOffset.UTC);

        JoinSessionResult result = sessionService.joinSession(
                host.getId(), group.getId(), saturday, saturday.plus(1, ChronoUnit.HOURS),
                10, "guest@test.com", "Guest", Duration.ofMinutes(15));

        Map<String, Object> lineage = lineageOf(result.sessionId());
        assertThat(result.sessionId()).isNotNull();
        assertThat(lineage.get("reservation_window_id")).isNull();
        // The occurrence key is still stamped, so even ad-hoc sessions know where
        // they started.
        assertThat(((Timestamp) lineage.get("scheduled_occurrence_start")).toInstant())
                .isEqualTo(saturday);
    }
}
