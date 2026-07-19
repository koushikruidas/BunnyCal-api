package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.RecurrenceFrequency;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.dto.ReservationWindowRequest;
import io.bunnycal.availability.service.GroupEventReservationWindowService;
import io.bunnycal.session.dto.PinnedSessionResponse;
import io.bunnycal.session.dto.SeriesCancelPreviewResponse;
import io.bunnycal.session.dto.SeriesOperationResponse;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionCompletionScheduler;
import io.bunnycal.session.service.SessionSeriesService;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Series-level host operations: pinning on rule change, bulk move, series cancel, and
 * the completion sweep.
 *
 * <p>The behavior these lock down is that a host editing a recurring schedule never
 * silently relocates guests who already hold a seat.
 */
class SessionSeriesOperationsIT extends AbstractSessionIT {

    @Autowired private SessionService sessionService;
    @Autowired private SessionSeriesService seriesService;
    @Autowired private GroupEventReservationWindowService windowService;
    @Autowired private SessionCompletionScheduler completionScheduler;

    private User hostWithFullWeekAvailability() {
        User host = createHost(); // UTC
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")) {
            jdbc.update("""
                    INSERT INTO availability_rules
                        (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                    VALUES (?, ?, ?, '09:00', '17:00', NOW(), NOW())
                    """, UUID.randomUUID(), host.getId(), day);
        }
        return host;
    }

    private UUID window(User host, EventType group, DayOfWeek day) {
        return windowService.replaceWindows(host.getId(), group.getId(),
                List.of(new ReservationWindowRequest(
                        null, ScheduleType.RECURRING, LocalTime.of(9, 0), LocalTime.of(17, 0),
                        null, day, RecurrenceFrequency.WEEKLY,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        RecurrenceEndMode.NONE, null, null)))
                .get(0).id();
    }

    private void moveWindowTo(User host, EventType group, UUID windowId, DayOfWeek day) {
        windowService.replaceWindows(host.getId(), group.getId(),
                List.of(new ReservationWindowRequest(
                        windowId, ScheduleType.RECURRING, LocalTime.of(9, 0), LocalTime.of(17, 0),
                        null, day, RecurrenceFrequency.WEEKLY,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        RecurrenceEndMode.NONE, null, null)));
    }

    private Instant nextDayAt9(DayOfWeek day) {
        return LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.next(day))
                .atTime(LocalTime.of(9, 0)).toInstant(ZoneOffset.UTC);
    }

    /** Books and confirms one guest, returning the session id. */
    private UUID bookedSession(User host, EventType group, Instant start, String email) {
        JoinSessionResult joined = sessionService.joinSession(
                host.getId(), group.getId(), start, start.plus(1, ChronoUnit.HOURS),
                group.getCapacity(), email, email, Duration.ofMinutes(15));
        sessionService.confirmRegistration(joined.sessionId(), joined.registrationId(), host.getId());
        return joined.sessionId();
    }

    @Test
    void changingTheRuleDay_pinsBookedSessionsInPlace() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        UUID windowId = window(host, group, DayOfWeek.MONDAY);

        Instant monday = nextDayAt9(DayOfWeek.MONDAY);
        UUID sessionId = bookedSession(host, group, monday, "alice@test.com");

        moveWindowTo(host, group, windowId, DayOfWeek.TUESDAY);

        // The booked session keeps its Monday time and its guest — it did not follow
        // the rule to Tuesday.
        var row = jdbc.queryForMap("""
                SELECT start_time, detached_at, detached_reason, confirmed_count
                FROM event_sessions WHERE id = ?
                """, sessionId);
        assertThat(((Timestamp) row.get("start_time")).toInstant()).isEqualTo(monday);
        assertThat(row.get("detached_reason")).isEqualTo("RULE_CHANGED");
        assertThat(row.get("detached_at")).isNotNull();
        assertThat(row.get("confirmed_count")).isEqualTo(1);
    }

    @Test
    void pinnedSessions_areListedForTheHostWithTheirOriginalOccurrence() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        UUID windowId = window(host, group, DayOfWeek.MONDAY);
        Instant monday = nextDayAt9(DayOfWeek.MONDAY);
        bookedSession(host, group, monday, "alice@test.com");

        moveWindowTo(host, group, windowId, DayOfWeek.TUESDAY);

        List<PinnedSessionResponse> pinned =
                seriesService.listPinnedSessions(host.getId(), group.getId());
        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).startTime()).isEqualTo(monday);
        // Both times are surfaced so the host can see the divergence, not just a date.
        assertThat(pinned.get(0).scheduledOccurrenceStart()).isEqualTo(monday);
        assertThat(pinned.get(0).detachedReason()).isEqualTo("RULE_CHANGED");
        assertThat(pinned.get(0).confirmedCount()).isEqualTo(1);
    }

    @Test
    void emptySessions_areNotPinned() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        UUID windowId = window(host, group, DayOfWeek.MONDAY);

        // A session that exists but has no confirmed guest (hold only) has nobody to
        // surprise, so the rule change should not pin it.
        Instant monday = nextDayAt9(DayOfWeek.MONDAY);
        sessionService.joinSession(host.getId(), group.getId(), monday,
                monday.plus(1, ChronoUnit.HOURS), 10, "held@test.com", "Held",
                Duration.ofMinutes(15));

        moveWindowTo(host, group, windowId, DayOfWeek.TUESDAY);

        assertThat(seriesService.listPinnedSessions(host.getId(), group.getId())).isEmpty();
    }

    @Test
    void extendingTheRecurrenceEndDate_doesNotPinAnything() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        UUID windowId = window(host, group, DayOfWeek.MONDAY);
        bookedSession(host, group, nextDayAt9(DayOfWeek.MONDAY), "alice@test.com");

        // Changing only the end bound leaves every remaining occurrence exactly where
        // it was, so booked sessions keep tracking the rule.
        windowService.replaceWindows(host.getId(), group.getId(),
                List.of(new ReservationWindowRequest(
                        windowId, ScheduleType.RECURRING, LocalTime.of(9, 0), LocalTime.of(17, 0),
                        null, DayOfWeek.MONDAY, RecurrenceFrequency.WEEKLY,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        RecurrenceEndMode.UNTIL_DATE,
                        LocalDate.now(ZoneOffset.UTC).plusMonths(6), null)));

        assertThat(seriesService.listPinnedSessions(host.getId(), group.getId())).isEmpty();
    }

    @Test
    void removingAWindow_pinsItsBookedSessions() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        window(host, group, DayOfWeek.MONDAY);
        UUID sessionId = bookedSession(host, group, nextDayAt9(DayOfWeek.MONDAY), "alice@test.com");

        // Retire the rule entirely; the booked session must survive with its guest.
        windowService.replaceWindows(host.getId(), group.getId(), List.of());

        var row = jdbc.queryForMap(
                "SELECT status, detached_reason, confirmed_count FROM event_sessions WHERE id = ?",
                sessionId);
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("detached_reason")).isEqualTo("RULE_CHANGED");
        assertThat(row.get("confirmed_count")).isEqualTo(1);
    }

    @Test
    void cancelSeriesPreview_reportsSessionsAndGuestsWithoutChangingAnything() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        Instant monday = nextDayAt9(DayOfWeek.MONDAY);
        bookedSession(host, group, monday, "alice@test.com");
        bookedSession(host, group, monday, "bob@test.com");
        bookedSession(host, group, monday.plus(7, ChronoUnit.DAYS), "carol@test.com");

        SeriesCancelPreviewResponse preview =
                seriesService.previewCancelSeries(host.getId(), group.getId(), null);

        assertThat(preview.sessionCount()).isEqualTo(2);
        assertThat(preview.affectedGuestCount()).isEqualTo(3);
        // Preview is read-only.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE event_type_id = ? AND status = 'CANCELLED'",
                Integer.class, group.getId())).isZero();
    }

    @Test
    void cancelSeries_softCancelsFutureSessionsAndTheirRegistrations() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 10);
        Instant monday = nextDayAt9(DayOfWeek.MONDAY);
        UUID first = bookedSession(host, group, monday, "alice@test.com");
        UUID second = bookedSession(host, group, monday.plus(7, ChronoUnit.DAYS), "bob@test.com");

        SeriesOperationResponse result =
                seriesService.cancelSeries(host.getId(), group.getId(), null);

        assertThat(result.affectedCount()).isEqualTo(2);
        // Soft cancel: rows survive with their history intact, never deleted.
        for (UUID id : List.of(first, second)) {
            assertThat(querySession(id).get("status")).isEqualTo("CANCELLED");
            assertThat(countRegistrationsByStatus(id, "CANCELLED")).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE event_type_id = ?",
                Integer.class, group.getId())).isEqualTo(2);
    }

    @Test
    void cancelSeries_leavesPastSessionsAlone() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 10);

        // A session that already happened, inserted directly so it can be in the past.
        UUID pastId = UUID.randomUUID();
        Instant pastStart = Instant.now().minus(10, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO event_sessions
                    (id, host_id, event_type_id, start_time, end_time, status, capacity,
                     confirmed_count, version, calendar_sequence, terminal_intent_epoch,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', 10, 1, 0, 0, 0, NOW(), NOW())
                """, pastId, host.getId(), group.getId(),
                Timestamp.from(pastStart), Timestamp.from(pastStart.plus(1, ChronoUnit.HOURS)));

        bookedSession(host, group, nextDayAt9(DayOfWeek.MONDAY), "alice@test.com");
        seriesService.cancelSeries(host.getId(), group.getId(), null);

        // The series stops going forward; it is not erased backwards.
        assertThat(querySession(pastId).get("status")).isEqualTo("OPEN");
    }

    @Test
    void completionSweep_transitionsFinishedSessionsToCompleted() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 10);

        UUID finishedId = UUID.randomUUID();
        Instant start = Instant.now().minus(3, ChronoUnit.HOURS);
        jdbc.update("""
                INSERT INTO event_sessions
                    (id, host_id, event_type_id, start_time, end_time, status, capacity,
                     confirmed_count, version, calendar_sequence, terminal_intent_epoch,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', 10, 2, 0, 0, 0, NOW(), NOW())
                """, finishedId, host.getId(), group.getId(),
                Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)));

        // A future session must not be swept.
        UUID futureId = bookedSession(host, group, nextDayAt9(DayOfWeek.MONDAY), "alice@test.com");

        completionScheduler.completeFinishedSessions();

        // COMPLETED was previously unreachable, which left the terminal-state guards in
        // reschedule/cancel as dead code.
        assertThat(querySession(finishedId).get("status")).isEqualTo("COMPLETED");
        assertThat(querySession(futureId).get("status")).isEqualTo("OPEN");
    }

    @Test
    void completedSession_canNoLongerBeRescheduled() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 10);

        UUID finishedId = UUID.randomUUID();
        Instant start = Instant.now().minus(3, ChronoUnit.HOURS);
        jdbc.update("""
                INSERT INTO event_sessions
                    (id, host_id, event_type_id, start_time, end_time, status, capacity,
                     confirmed_count, version, calendar_sequence, terminal_intent_epoch,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', 10, 1, 0, 0, 0, NOW(), NOW())
                """, finishedId, host.getId(), group.getId(),
                Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)));

        completionScheduler.completeFinishedSessions();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        sessionService.rescheduleSession(finishedId, host.getId(),
                                Instant.now().plus(2, ChronoUnit.DAYS)))
                .isInstanceOf(io.bunnycal.common.exception.CustomException.class)
                .hasMessageContaining("Only active meetings");
    }

    @Test
    void loweringEventTypeCapacity_leavesExistingSessionsAndTheirGuestsUntouched() {
        User host = hostWithFullWeekAvailability();
        EventType group = createGroupEventType(host.getId(), 3);
        Instant monday = nextDayAt9(DayOfWeek.MONDAY);

        UUID sessionId = bookedSession(host, group, monday, "alice@test.com");
        bookedSession(host, group, monday, "bob@test.com");
        bookedSession(host, group, monday, "carol@test.com");
        assertThat(querySession(sessionId).get("confirmed_count")).isEqualTo(3);

        // Host lowers capacity on the event type. Sessions snapshot capacity when they
        // are materialized and it is never rewritten afterwards, so an existing session
        // cannot be pushed over its own limit — the DB check (confirmed_count <=
        // capacity) makes that state unrepresentable rather than merely discouraged.
        group.setCapacity(2);
        eventTypeRepository.save(group);

        // Nobody is evicted and the already-booked session keeps its original ceiling.
        assertThat(querySession(sessionId).get("confirmed_count")).isEqualTo(3);
        assertThat(querySession(sessionId).get("capacity")).isEqualTo(3);
        assertThat(countRegistrationsByStatus(sessionId, "CONFIRMED")).isEqualTo(3);

        // A session created after the change picks up the lower capacity.
        Instant tuesday = nextDayAt9(DayOfWeek.TUESDAY);
        UUID newer = bookedSession(host, group, tuesday, "dave@test.com");
        assertThat(querySession(newer).get("capacity")).isEqualTo(2);
    }

    @Test
    void hostCanMoveASessionOutsideTheirAvailability() {
        User host = hostWithFullWeekAvailability(); // weekdays 09:00-17:00 only
        EventType group = createGroupEventType(host.getId(), 10);
        window(host, group, DayOfWeek.MONDAY);

        Instant monday = nextDayAt9(DayOfWeek.MONDAY);
        UUID sessionId = bookedSession(host, group, monday, "alice@test.com");

        // Sunday 03:00 — no availability rule covers it and no reservation window
        // generates it. A host moving their own meeting is an explicit override, not an
        // automated scheduling decision, so the backend must allow it.
        Instant sundayNight = nextDayAt9(DayOfWeek.SUNDAY).minus(6, ChronoUnit.HOURS);
        sessionService.rescheduleSession(sessionId, host.getId(), sundayNight);

        var row = jdbc.queryForMap(
                "SELECT start_time, detached_reason FROM event_sessions WHERE id = ?", sessionId);
        assertThat(((Timestamp) row.get("start_time")).toInstant()).isEqualTo(sundayNight);
        assertThat(row.get("detached_reason")).isEqualTo("HOST_RESCHEDULED");
        // The guest travels with the session rather than being dropped.
        assertThat(countRegistrationsByStatus(sessionId, "CONFIRMED")).isEqualTo(1);
    }
}
