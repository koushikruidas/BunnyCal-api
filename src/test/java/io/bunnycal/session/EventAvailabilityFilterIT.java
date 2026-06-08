package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.EventAvailabilityWindowRequest;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.service.EventAvailabilityWindowService;
import io.bunnycal.availability.service.SlotService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Event Availability FILTER for demand-driven event types (ONE_ON_ONE, ROUND_ROBIN,
 * COLLECTIVE).
 *
 * The filter only narrows the host's availability for the owning event type. It
 * reserves no time, blocks no other event type, and never modifies host availability.
 * Covers required tests I-O.
 */
class EventAvailabilityFilterIT extends AbstractSessionIT {

    @Autowired private SlotService slotService;
    @Autowired private EventAvailabilityWindowService availabilityWindowService;

    /** 2026-06-17 is a Wednesday, well within maxAdvance. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 6, 17);

    private Instant slotAt(int hour, int minute) {
        return WEDNESDAY.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    /** Host available Mon-Fri 09:00-17:00 (global availability). */
    private User createHostWithWeekdayAvailability() {
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

    private EventType createDemandDrivenType(UUID hostId, EventKind kind) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId)
                .name(kind.name())
                .slug(kind.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(15))
                .kind(kind)
                .capacity(kind == EventKind.GROUP ? 20 : 1)
                .build());
    }

    private EventAvailabilityWindowRequest window(DayOfWeek day, String start, String end) {
        return new EventAvailabilityWindowRequest(day, LocalTime.parse(start), LocalTime.parse(end));
    }

    private List<Instant> slotStarts(UUID hostId, UUID eventTypeId) {
        SlotResponse response = slotService.getSlots(new SlotRequest(hostId, eventTypeId, WEDNESDAY));
        return response.slots().stream().map(SlotDto::start).toList();
    }

    private int countWindows(UUID eventTypeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_availability_windows WHERE event_type_id = ?",
                Integer.class, eventTypeId);
        return count == null ? 0 : count;
    }

    private int countHostRules(UUID hostId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM availability_rules WHERE user_id = ?", Integer.class, hostId);
        return count == null ? 0 : count;
    }

    // ── I: One-to-One availability filter limits visible slots ────────────────

    @Test
    void oneOnOneAvailabilityFilter_limitsVisibleSlots() {
        User host = createHostWithWeekdayAvailability(); // 09:00-17:00
        EventType oneOnOne = createDemandDrivenType(host.getId(), EventKind.ONE_ON_ONE);

        // Filter the One-to-One down to 10:00-12:00 on Wednesday.
        availabilityWindowService.replaceWindows(host.getId(), oneOnOne.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "10:00", "12:00")));

        List<Instant> starts = slotStarts(host.getId(), oneOnOne.getId());
        // Inside the filter -> visible.
        assertThat(starts).contains(slotAt(10, 0), slotAt(11, 0), slotAt(11, 30));
        // Outside the filter (still inside host availability) -> hidden.
        assertThat(starts).doesNotContain(slotAt(9, 0), slotAt(9, 30), slotAt(12, 0), slotAt(14, 0));
        // Last slot must fit fully within 12:00.
        assertThat(starts).doesNotContain(slotAt(12, 0));
    }

    // ── J: One-to-One filter does not block ANOTHER One-to-One event ──────────

    @Test
    void oneOnOneAvailabilityFilter_doesNotBlockAnotherOneOnOne() {
        User host = createHostWithWeekdayAvailability();
        EventType filtered = createDemandDrivenType(host.getId(), EventKind.ONE_ON_ONE);
        EventType unfiltered = createDemandDrivenType(host.getId(), EventKind.ONE_ON_ONE);

        availabilityWindowService.replaceWindows(host.getId(), filtered.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "10:00", "12:00")));

        // The second One-to-One has NO filter -> sees the full host availability,
        // unaffected by the first event's filter (no ownership / no blocking).
        List<Instant> unfilteredStarts = slotStarts(host.getId(), unfiltered.getId());
        assertThat(unfilteredStarts).contains(slotAt(9, 0), slotAt(9, 30), slotAt(16, 30));
        // Both events overlap on 10:00-12:00 -- a filter is not a reservation.
        assertThat(unfilteredStarts).contains(slotAt(10, 0), slotAt(11, 0));
        assertThat(slotStarts(host.getId(), filtered.getId())).contains(slotAt(10, 0), slotAt(11, 0));
    }

    // ── K: Round Robin filter does not reserve time ───────────────────────────

    @Test
    void roundRobinAvailabilityFilter_doesNotReserveTime() {
        User host = createHostWithWeekdayAvailability();
        EventType roundRobin = createDemandDrivenType(host.getId(), EventKind.ROUND_ROBIN);
        EventType oneOnOne = createDemandDrivenType(host.getId(), EventKind.ONE_ON_ONE);

        availabilityWindowService.replaceWindows(host.getId(), roundRobin.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "10:00", "12:00")));

        // Round Robin sees only its filtered window...
        assertThat(slotStarts(host.getId(), roundRobin.getId())).contains(slotAt(10, 0));
        assertThat(slotStarts(host.getId(), roundRobin.getId())).doesNotContain(slotAt(9, 0));
        // ...but the One-to-One is NOT blocked during 10:00-12:00 (no reservation).
        assertThat(slotStarts(host.getId(), oneOnOne.getId())).contains(slotAt(10, 0), slotAt(11, 0));
    }

    // ── L: Collective filter does not reserve time ────────────────────────────

    @Test
    void collectiveAvailabilityFilter_doesNotReserveTime() {
        User host = createHostWithWeekdayAvailability();
        EventType collective = createDemandDrivenType(host.getId(), EventKind.COLLECTIVE);
        EventType oneOnOne = createDemandDrivenType(host.getId(), EventKind.ONE_ON_ONE);

        availabilityWindowService.replaceWindows(host.getId(), collective.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "13:00", "15:00")));

        assertThat(slotStarts(host.getId(), collective.getId())).contains(slotAt(13, 0), slotAt(14, 0));
        assertThat(slotStarts(host.getId(), collective.getId())).doesNotContain(slotAt(9, 0), slotAt(16, 0));
        // One-to-One unaffected -- the collective's filter reserves nothing.
        assertThat(slotStarts(host.getId(), oneOnOne.getId())).contains(slotAt(13, 0), slotAt(14, 0));
    }

    // ── M: demand-driven event availability never modifies host availability ──

    @Test
    void demandDrivenAvailabilityFilter_neverModifiesHostAvailability() {
        User host = createHostWithWeekdayAvailability();
        EventType oneOnOne = createDemandDrivenType(host.getId(), EventKind.ONE_ON_ONE);

        int rulesBefore = countHostRules(host.getId());
        assertThat(rulesBefore).isEqualTo(5); // Mon-Fri

        availabilityWindowService.replaceWindows(host.getId(), oneOnOne.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "10:00", "12:00")));

        // Host availability rows are untouched -- the filter lives in its own table.
        assertThat(countHostRules(host.getId())).isEqualTo(rulesBefore);
        assertThat(countWindows(oneOnOne.getId())).isEqualTo(1);
    }

    // ── N: creating/editing an event type does not touch availability_rules ───

    @Test
    void replacingFilterWindows_doesNotWriteAvailabilityRules() {
        User host = createHostWithWeekdayAvailability();
        EventType oneOnOne = createDemandDrivenType(host.getId(), EventKind.ONE_ON_ONE);

        int rulesBefore = countHostRules(host.getId());

        // Replace twice (simulating create then edit).
        availabilityWindowService.replaceWindows(host.getId(), oneOnOne.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "10:00", "12:00")));
        availabilityWindowService.replaceWindows(host.getId(), oneOnOne.getId(),
                List.of(window(DayOfWeek.THURSDAY, "13:00", "16:00")));

        assertThat(countHostRules(host.getId())).isEqualTo(rulesBefore);
        // The latest replace fully supersedes the previous set (bulk upsert).
        assertThat(countWindows(oneOnOne.getId())).isEqualTo(1);
    }

    // ── O: empty filter means full host availability (back-compat default) ────

    @Test
    void noFilter_seesFullHostAvailability() {
        User host = createHostWithWeekdayAvailability();
        EventType oneOnOne = createDemandDrivenType(host.getId(), EventKind.ONE_ON_ONE);

        assertThat(countWindows(oneOnOne.getId())).isZero();
        // 09:00-17:00, 30-min grid -> 09:00 .. 16:30 all visible.
        List<Instant> starts = slotStarts(host.getId(), oneOnOne.getId());
        assertThat(starts).contains(slotAt(9, 0), slotAt(9, 30), slotAt(16, 30));
    }

    // ── validation: GROUP rejected; filter must be within host availability ───

    @Test
    void availabilityFilter_rejectedForGroupEventType() {
        User host = createHostWithWeekdayAvailability();
        EventType group = createDemandDrivenType(host.getId(), EventKind.GROUP);

        assertThatThrownBy(() -> availabilityWindowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "10:00", "12:00"))))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void availabilityFilter_outsideHostAvailability_rejected() {
        User host = createHostWithWeekdayAvailability(); // 09:00-17:00
        EventType oneOnOne = createDemandDrivenType(host.getId(), EventKind.ONE_ON_ONE);

        assertThatThrownBy(() -> availabilityWindowService.replaceWindows(
                host.getId(), oneOnOne.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "18:00", "19:00"))))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void availabilityFilter_overlappingAnotherEventAllowed() {
        User host = createHostWithWeekdayAvailability();
        EventType a = createDemandDrivenType(host.getId(), EventKind.ONE_ON_ONE);
        EventType b = createDemandDrivenType(host.getId(), EventKind.ROUND_ROBIN);

        availabilityWindowService.replaceWindows(host.getId(), a.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "10:00", "12:00")));
        // Overlap across events is allowed -- filters create no ownership.
        var saved = availabilityWindowService.replaceWindows(host.getId(), b.getId(),
                List.of(window(DayOfWeek.WEDNESDAY, "11:00", "13:00")));
        assertThat(saved).hasSize(1);
    }
}
