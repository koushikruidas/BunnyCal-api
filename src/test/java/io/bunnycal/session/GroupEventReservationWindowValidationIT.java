package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.RecurrenceFrequency;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.dto.ReservationWindowRequest;
import io.bunnycal.availability.service.GroupEventReservationWindowService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation rules for reservation-window create/update:
 *  - GROUP only
 *  - within host availability
 *  - no overlap with other group events' windows (and none within the request)
 *  - ONE_TIME and RECURRING field requirements
 */
class GroupEventReservationWindowValidationIT extends AbstractSessionIT {

    @Autowired private GroupEventReservationWindowService reservationWindowService;

    private static final LocalDate FUTURE_DATE = LocalDate.of(2026, 8, 5); // Wednesday

    private User hostWithWeekdayAvailability() {
        User host = createHost();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")) {
            jdbc.update("""
                    INSERT INTO availability_rules
                        (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                    VALUES (?, ?, ?, '09:00', '17:00', NOW(), NOW())
                    """, UUID.randomUUID(), host.getId(), day);
        }
        return host;
    }

    private EventType groupType(UUID hostId) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId).name("Group").slug("g-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30)).bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30)).minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(15)).kind(EventKind.GROUP).capacity(20).build());
    }

    private EventType oneOnOneType(UUID hostId) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId).name("1on1").slug("o-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30)).bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30)).minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(15)).kind(EventKind.ONE_ON_ONE).capacity(1).build());
    }

    /** RECURRING window with NONE end mode (backward-compatible style). */
    private ReservationWindowRequest recurringWindow(DayOfWeek day, String start, String end) {
        return new ReservationWindowRequest(
                null, ScheduleType.RECURRING, LocalTime.parse(start), LocalTime.parse(end),
                null, day, RecurrenceFrequency.WEEKLY,
                LocalDate.of(2026, 8, 3), RecurrenceEndMode.NONE, null, null);
    }

    /** ONE_TIME window on a given date. */
    private ReservationWindowRequest oneTimeWindow(LocalDate date, String start, String end) {
        return new ReservationWindowRequest(
                null, ScheduleType.ONE_TIME, LocalTime.parse(start), LocalTime.parse(end),
                date, null, null, null, null, null, null);
    }

    // ── GROUP-only ───────────────────────────────────────────────────────────

    @Test
    void reservationWindow_rejectedForNonGroupEventType() {
        User host = hostWithWeekdayAvailability();
        EventType oneOnOne = oneOnOneType(host.getId());

        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), oneOnOne.getId(),
                List.of(recurringWindow(DayOfWeek.WEDNESDAY, "09:00", "11:00"))))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ── within host availability ──────────────────────────────────────────────

    @Test
    void reservationWindow_withinHostAvailability_accepted() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        var saved = reservationWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(recurringWindow(DayOfWeek.WEDNESDAY, "10:00", "12:00")));

        assertThat(saved).hasSize(1);
    }

    @Test
    void reservationWindow_outsideHostAvailability_rejected() {
        User host = hostWithWeekdayAvailability(); // 09:00-17:00
        EventType group = groupType(host.getId());

        // 18:00-19:00 is outside the host's 09:00-17:00 availability.
        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(recurringWindow(DayOfWeek.WEDNESDAY, "18:00", "19:00"))))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void reservationWindow_onDayHostNotAvailable_rejected() {
        User host = hostWithWeekdayAvailability(); // Mon-Fri only
        EventType group = groupType(host.getId());

        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(recurringWindow(DayOfWeek.SATURDAY, "10:00", "12:00"))))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ── no overlap with other group events ────────────────────────────────────

    @Test
    void reservationWindow_overlappingAnotherGroupEvent_rejected() {
        User host = hostWithWeekdayAvailability();
        EventType groupA = groupType(host.getId());
        EventType groupB = groupType(host.getId());

        reservationWindowService.replaceWindows(
                host.getId(), groupA.getId(),
                List.of(recurringWindow(DayOfWeek.WEDNESDAY, "10:00", "12:00")));

        // Group B tries to reserve an overlapping band on the same day -> rejected.
        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), groupB.getId(),
                List.of(recurringWindow(DayOfWeek.WEDNESDAY, "11:00", "13:00"))))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void reservationWindow_nonOverlappingDifferentGroupEvent_accepted() {
        User host = hostWithWeekdayAvailability();
        EventType groupA = groupType(host.getId());
        EventType groupB = groupType(host.getId());

        reservationWindowService.replaceWindows(
                host.getId(), groupA.getId(),
                List.of(recurringWindow(DayOfWeek.WEDNESDAY, "09:00", "11:00")));

        // Group B reserves a different, non-overlapping band -> accepted.
        var saved = reservationWindowService.replaceWindows(
                host.getId(), groupB.getId(),
                List.of(recurringWindow(DayOfWeek.WEDNESDAY, "11:00", "13:00")));
        assertThat(saved).hasSize(1);
    }

    @Test
    void reservationWindow_updatingSameEventTypeDoesNotConflictWithItself() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        reservationWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(recurringWindow(DayOfWeek.WEDNESDAY, "10:00", "12:00")));

        // Re-submitting an overlapping window for the SAME event type is a replace,
        // not a conflict (its own prior rows are excluded from the overlap check).
        var saved = reservationWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(recurringWindow(DayOfWeek.WEDNESDAY, "10:30", "12:30")));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).startTime()).isEqualTo(LocalTime.of(10, 30));
    }

    @Test
    void reservationWindow_selfOverlappingWithinRequest_rejected() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(
                        recurringWindow(DayOfWeek.WEDNESDAY, "09:00", "11:00"),
                        recurringWindow(DayOfWeek.WEDNESDAY, "10:00", "12:00"))))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ── ONE_TIME validation ───────────────────────────────────────────────────

    @Test
    void oneTime_withValidEventDate_accepted() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());
        // FUTURE_DATE = 2026-08-05 (Wednesday), within host availability
        var saved = reservationWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(oneTimeWindow(FUTURE_DATE, "10:00", "12:00")));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).eventDate()).isEqualTo(FUTURE_DATE);
    }

    @Test
    void oneTime_withNullEventDate_rejected() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        // eventDate is null — should fail validation
        ReservationWindowRequest badRequest = new ReservationWindowRequest(
                null, ScheduleType.ONE_TIME, LocalTime.parse("10:00"), LocalTime.parse("12:00"),
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), group.getId(), List.of(badRequest)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ── RECURRING validation ─────────────────────────────────────────────────

    @Test
    void recurring_withNullStartDate_rejected() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        // startDate is null — should fail validation
        ReservationWindowRequest badRequest = new ReservationWindowRequest(
                null, ScheduleType.RECURRING, LocalTime.parse("10:00"), LocalTime.parse("12:00"),
                null, DayOfWeek.WEDNESDAY, RecurrenceFrequency.WEEKLY,
                null, RecurrenceEndMode.NONE, null, null);

        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), group.getId(), List.of(badRequest)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void recurring_untilDateBeforeStartDate_rejected() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        LocalDate startDate = LocalDate.of(2026, 8, 3);
        LocalDate untilDate = LocalDate.of(2026, 7, 1); // before startDate

        ReservationWindowRequest badRequest = new ReservationWindowRequest(
                null, ScheduleType.RECURRING, LocalTime.parse("10:00"), LocalTime.parse("12:00"),
                null, DayOfWeek.MONDAY, RecurrenceFrequency.WEEKLY,
                startDate, RecurrenceEndMode.UNTIL_DATE, untilDate, null);

        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), group.getId(), List.of(badRequest)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void recurring_occurrenceCountZero_rejected() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        ReservationWindowRequest badRequest = new ReservationWindowRequest(
                null, ScheduleType.RECURRING, LocalTime.parse("10:00"), LocalTime.parse("12:00"),
                null, DayOfWeek.MONDAY, RecurrenceFrequency.WEEKLY,
                LocalDate.of(2026, 8, 3), RecurrenceEndMode.OCCURRENCE_COUNT, null, 0);

        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), group.getId(), List.of(badRequest)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ── ONE_TIME cross-event overlap ──────────────────────────────────────────

    @Test
    void oneTime_overlappingAnotherGroupEventOnSameDate_rejected() {
        User host = hostWithWeekdayAvailability();
        EventType groupA = groupType(host.getId());
        EventType groupB = groupType(host.getId());

        // Group A reserves 2026-08-05 10:00-12:00
        reservationWindowService.replaceWindows(
                host.getId(), groupA.getId(),
                List.of(oneTimeWindow(FUTURE_DATE, "10:00", "12:00")));

        // Group B tries to reserve same date with overlapping time → rejected
        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), groupB.getId(),
                List.of(oneTimeWindow(FUTURE_DATE, "11:00", "13:00"))))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void oneTime_nonOverlappingOnSameDate_accepted() {
        User host = hostWithWeekdayAvailability();
        EventType groupA = groupType(host.getId());
        EventType groupB = groupType(host.getId());

        // Group A reserves 2026-08-05 09:00-11:00
        reservationWindowService.replaceWindows(
                host.getId(), groupA.getId(),
                List.of(oneTimeWindow(FUTURE_DATE, "09:00", "11:00")));

        // Group B reserves non-overlapping time on the same date → accepted
        var saved = reservationWindowService.replaceWindows(
                host.getId(), groupB.getId(),
                List.of(oneTimeWindow(FUTURE_DATE, "11:00", "13:00")));
        assertThat(saved).hasSize(1);
    }
}
