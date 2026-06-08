package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.ReservationWindowRequest;
import io.bunnycal.availability.service.GroupEventReservationWindowService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.DayOfWeek;
import java.time.Duration;
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
 */
class GroupEventReservationWindowValidationIT extends AbstractSessionIT {

    @Autowired private GroupEventReservationWindowService reservationWindowService;

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

    private ReservationWindowRequest window(DayOfWeek day, String start, String end) {
        return new ReservationWindowRequest(day, LocalTime.parse(start), LocalTime.parse(end));
    }

    // ── GROUP-only ───────────────────────────────────────────────────────────

    @Test
    void reservationWindow_rejectedForNonGroupEventType() {
        User host = hostWithWeekdayAvailability();
        EventType oneOnOne = oneOnOneType(host.getId());

        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), oneOnOne.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "09:00", "11:00"))))
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
                List.of(window(DayOfWeek.WEDNESDAY, "10:00", "12:00")));

        assertThat(saved).hasSize(1);
    }

    @Test
    void reservationWindow_outsideHostAvailability_rejected() {
        User host = hostWithWeekdayAvailability(); // 09:00-17:00
        EventType group = groupType(host.getId());

        // 18:00-19:00 is outside the host's 09:00-17:00 availability.
        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "18:00", "19:00"))))
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
                List.of(window(DayOfWeek.SATURDAY, "10:00", "12:00"))))
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
                List.of(window(DayOfWeek.WEDNESDAY, "10:00", "12:00")));

        // Group B tries to reserve an overlapping band on the same day -> rejected.
        assertThatThrownBy(() -> reservationWindowService.replaceWindows(
                host.getId(), groupB.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "11:00", "13:00"))))
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
                List.of(window(DayOfWeek.WEDNESDAY, "09:00", "11:00")));

        // Group B reserves a different, non-overlapping band -> accepted.
        var saved = reservationWindowService.replaceWindows(
                host.getId(), groupB.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "11:00", "13:00")));
        assertThat(saved).hasSize(1);
    }

    @Test
    void reservationWindow_updatingSameEventTypeDoesNotConflictWithItself() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        reservationWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "10:00", "12:00")));

        // Re-submitting an overlapping window for the SAME event type is a replace,
        // not a conflict (its own prior rows are excluded from the overlap check).
        var saved = reservationWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "10:30", "12:30")));
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
                        window(DayOfWeek.WEDNESDAY, "09:00", "11:00"),
                        window(DayOfWeek.WEDNESDAY, "10:00", "12:00"))))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
