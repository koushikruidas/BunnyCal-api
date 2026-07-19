package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.RecurrenceFrequency;
import io.bunnycal.availability.domain.ReservationWindowStatus;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.dto.ReservationWindowRequest;
import io.bunnycal.availability.dto.ReservationWindowResponse;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.availability.service.GroupEventReservationWindowService;
import io.bunnycal.common.exception.CustomException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Window identity across edits — the foundation the pinned-exception behavior rests on.
 *
 * <p>Before lineage tracking, {@code replaceWindows} deleted every row and re-inserted,
 * so window ids changed on every edit and nothing could link a session back to the rule
 * that produced it. These tests pin down the replacement behavior: ids survive edits,
 * removed windows are retired rather than deleted, and content-based ambiguity (splits
 * and merges) resolves by client-declared identity rather than server inference.
 */
class ReservationWindowIdentityIT extends AbstractSessionIT {

    @Autowired private GroupEventReservationWindowService windowService;
    @Autowired private GroupEventReservationWindowRepository windowRepository;
    @Autowired private io.bunnycal.session.service.SessionSeriesService seriesService;

    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 3); // Monday

    // ── Fixtures ───────────────────────────────────────────────────────────────

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

    /** New window (no id) — an insert. */
    private ReservationWindowRequest newWindow(DayOfWeek day, String start, String end) {
        return window(null, day, start, end);
    }

    /** Existing window (id present) — an in-place update. */
    private ReservationWindowRequest window(UUID id, DayOfWeek day, String start, String end) {
        return new ReservationWindowRequest(
                id, ScheduleType.RECURRING, LocalTime.parse(start), LocalTime.parse(end),
                null, day, RecurrenceFrequency.WEEKLY, ANCHOR, RecurrenceEndMode.NONE, null, null);
    }

    /** A booked session materialized under a given window. */
    private UUID bookedSession(UUID hostId, UUID eventTypeId, UUID windowId, Instant start) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event_sessions
                    (id, host_id, event_type_id, reservation_window_id, scheduled_occurrence_start,
                     start_time, end_time, status, capacity, confirmed_count, version,
                     calendar_sequence, terminal_intent_epoch, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', 20, 2, 1, 0, 0, NOW(), NOW())
                """, id, hostId, eventTypeId, windowId, Timestamp.from(start),
                Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))));
        return id;
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void bookedCounts_areReportedPerWindow() {
        User host = hostWithWeekdayAvailability();
        EventType et = groupType(host.getId());
        List<ReservationWindowResponse> saved = windowService.replaceWindows(
                host.getId(), et.getId(),
                List.of(newWindow(DayOfWeek.MONDAY, "10:00", "11:00"),
                        newWindow(DayOfWeek.TUESDAY, "10:00", "11:00")));

        UUID mondayId = saved.get(0).id();
        Instant future = Instant.now().plus(Duration.ofDays(7));
        bookedSession(host.getId(), et.getId(), mondayId, future);
        bookedSession(host.getId(), et.getId(), mondayId, future.plus(Duration.ofDays(7)));

        Map<UUID, Long> counts = seriesService.countBookedSessionsByWindow(host.getId(), et.getId());

        assertThat(counts).containsEntry(mondayId, 2L);
        // A window with no bookings is absent rather than zero — the UI treats missing as 0.
        assertThat(counts).hasSize(1);
    }

    @Test
    void bookedCounts_excludeDetachedAndPastSessions() {
        User host = hostWithWeekdayAvailability();
        EventType et = groupType(host.getId());
        List<ReservationWindowResponse> saved = windowService.replaceWindows(
                host.getId(), et.getId(), List.of(newWindow(DayOfWeek.MONDAY, "10:00", "11:00")));
        UUID windowId = saved.get(0).id();

        UUID detached = bookedSession(host.getId(), et.getId(), windowId,
                Instant.now().plus(Duration.ofDays(7)));
        jdbc.update("UPDATE event_sessions SET detached_at = NOW(), detached_reason = 'RULE_CHANGED' WHERE id = ?",
                detached);
        // Already in the past — editing the rule cannot pin what has already happened.
        bookedSession(host.getId(), et.getId(), windowId, Instant.now().minus(Duration.ofDays(2)));

        assertThat(seriesService.countBookedSessionsByWindow(host.getId(), et.getId())).isEmpty();
    }

    @Test
    void editingAWindowInPlace_keepsSessionLineageIntact() {
        User host = hostWithWeekdayAvailability();
        EventType et = groupType(host.getId());
        List<ReservationWindowResponse> saved = windowService.replaceWindows(
                host.getId(), et.getId(), List.of(newWindow(DayOfWeek.MONDAY, "10:00", "11:00")));
        UUID windowId = saved.get(0).id();
        UUID sessionId = bookedSession(host.getId(), et.getId(), windowId,
                Instant.now().plus(Duration.ofDays(7)));

        // The edit the UI now performs: same id, different day. This is what remove+add
        // could never do — it sent a null id, so the session was orphaned instead of pinned.
        windowService.replaceWindows(host.getId(), et.getId(),
                List.of(window(windowId, DayOfWeek.TUESDAY, "10:00", "11:00")));

        UUID lineage = jdbc.queryForObject(
                "SELECT reservation_window_id FROM event_sessions WHERE id = ?", UUID.class, sessionId);
        assertThat(lineage).isEqualTo(windowId);
        assertThat(jdbc.queryForObject(
                "SELECT detached_reason FROM event_sessions WHERE id = ?", String.class, sessionId))
                .isEqualTo("RULE_CHANGED");
    }

    @Test
    void editingOneWindow_preservesTheIdsOfTheOthers() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        List<ReservationWindowResponse> initial = windowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(newWindow(DayOfWeek.MONDAY, "09:00", "10:00"),
                        newWindow(DayOfWeek.TUESDAY, "09:00", "10:00")));

        ReservationWindowResponse monday = initial.stream()
                .filter(w -> w.dayOfWeek() == DayOfWeek.MONDAY).findFirst().orElseThrow();
        ReservationWindowResponse tuesday = initial.stream()
                .filter(w -> w.dayOfWeek() == DayOfWeek.TUESDAY).findFirst().orElseThrow();

        // Move Monday's time; leave Tuesday untouched.
        windowService.replaceWindows(host.getId(), group.getId(),
                List.of(window(monday.id(), DayOfWeek.MONDAY, "14:00", "15:00"),
                        window(tuesday.id(), DayOfWeek.TUESDAY, "09:00", "10:00")));

        List<ReservationWindowResponse> after = windowService.list(host.getId(), group.getId());
        assertThat(after).hasSize(2);
        // Both ids survive — the edited one included. Under the old delete-all
        // behavior every id here would be new.
        assertThat(after).extracting(ReservationWindowResponse::id)
                .containsExactlyInAnyOrder(monday.id(), tuesday.id());
        assertThat(after).filteredOn(w -> w.id().equals(monday.id()))
                .singleElement()
                .extracting(ReservationWindowResponse::startTime)
                .isEqualTo(LocalTime.parse("14:00"));
    }

    @Test
    void removedWindow_isRetiredNotDeleted() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        List<ReservationWindowResponse> initial = windowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(newWindow(DayOfWeek.MONDAY, "09:00", "10:00"),
                        newWindow(DayOfWeek.TUESDAY, "09:00", "10:00")));
        UUID mondayId = initial.stream()
                .filter(w -> w.dayOfWeek() == DayOfWeek.MONDAY).findFirst().orElseThrow().id();
        UUID tuesdayId = initial.stream()
                .filter(w -> w.dayOfWeek() == DayOfWeek.TUESDAY).findFirst().orElseThrow().id();

        // Submit only Tuesday: Monday is absent and should retire.
        windowService.replaceWindows(host.getId(), group.getId(),
                List.of(window(tuesdayId, DayOfWeek.TUESDAY, "09:00", "10:00")));

        // Gone from the live view...
        assertThat(windowService.list(host.getId(), group.getId()))
                .extracting(ReservationWindowResponse::id)
                .containsExactly(tuesdayId);

        // ...but the row survives, so sessions it generated keep resolvable lineage.
        var retired = windowRepository.findById(mondayId).orElseThrow();
        assertThat(retired.getStatus()).isEqualTo(ReservationWindowStatus.RETIRED);
        assertThat(retired.getRetiredAt()).isNotNull();
    }

    @Test
    void splittingOneWindowIntoTwo_keepsOriginalIdentityAndAddsOne() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        UUID originalId = windowService.replaceWindows(host.getId(), group.getId(),
                List.of(newWindow(DayOfWeek.MONDAY, "09:00", "12:00"))).get(0).id();

        // 09:00-12:00 becomes 09:00-10:00 (same window, shortened) + 11:00-12:00 (new).
        // Content-based matching could not have decided which half inherits identity;
        // the client declares it.
        windowService.replaceWindows(host.getId(), group.getId(),
                List.of(window(originalId, DayOfWeek.MONDAY, "09:00", "10:00"),
                        newWindow(DayOfWeek.MONDAY, "11:00", "12:00")));

        List<ReservationWindowResponse> after = windowService.list(host.getId(), group.getId());
        assertThat(after).hasSize(2);
        assertThat(after).extracting(ReservationWindowResponse::id).contains(originalId);
        assertThat(after).filteredOn(w -> w.id().equals(originalId))
                .singleElement()
                .extracting(ReservationWindowResponse::endTime)
                .isEqualTo(LocalTime.parse("10:00"));
    }

    @Test
    void mergingTwoWindowsIntoOne_retiresTheAbsorbedWindow() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());

        List<ReservationWindowResponse> initial = windowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(newWindow(DayOfWeek.MONDAY, "09:00", "10:00"),
                        newWindow(DayOfWeek.MONDAY, "11:00", "12:00")));
        UUID keptId = initial.stream()
                .filter(w -> w.startTime().equals(LocalTime.parse("09:00"))).findFirst().orElseThrow().id();
        UUID absorbedId = initial.stream()
                .filter(w -> w.startTime().equals(LocalTime.parse("11:00"))).findFirst().orElseThrow().id();

        // Both windows collapse into one 09:00-12:00 block.
        windowService.replaceWindows(host.getId(), group.getId(),
                List.of(window(keptId, DayOfWeek.MONDAY, "09:00", "12:00")));

        assertThat(windowService.list(host.getId(), group.getId()))
                .extracting(ReservationWindowResponse::id)
                .containsExactly(keptId);
        assertThat(windowRepository.findById(absorbedId).orElseThrow().getStatus())
                .isEqualTo(ReservationWindowStatus.RETIRED);
    }

    @Test
    void unknownWindowId_isRejectedRatherThanSilentlyInserted() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());
        windowService.replaceWindows(host.getId(), group.getId(),
                List.of(newWindow(DayOfWeek.MONDAY, "09:00", "10:00")));

        // A client sending an id it does not own is a bug. Treating it as an insert
        // would hide that while silently creating a duplicate window.
        assertThatThrownBy(() -> windowService.replaceWindows(host.getId(), group.getId(),
                List.of(window(UUID.randomUUID(), DayOfWeek.TUESDAY, "09:00", "10:00"))))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("does not belong to this event type");
    }

    @Test
    void legacyPayloadWithoutIds_stillReplacesAll() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());
        windowService.replaceWindows(host.getId(), group.getId(),
                List.of(newWindow(DayOfWeek.MONDAY, "09:00", "10:00")));

        // An old client sends no ids at all — replace-all semantics still apply so it
        // keeps working during the deploy gap.
        List<ReservationWindowResponse> after = windowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(newWindow(DayOfWeek.TUESDAY, "09:00", "10:00")));

        assertThat(after).hasSize(1);
        assertThat(after.get(0).dayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
    }

    @Test
    void mixedPayload_takesTheIdKeyedPathNotLegacy() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());
        UUID existingId = windowService.replaceWindows(host.getId(), group.getId(),
                List.of(newWindow(DayOfWeek.MONDAY, "09:00", "10:00"))).get(0).id();

        // "My existing windows, plus a new one" is the ordinary new-client shape. If a
        // mixed payload were treated as legacy, adding a window would wipe lineage.
        List<ReservationWindowResponse> after = windowService.replaceWindows(
                host.getId(), group.getId(),
                List.of(window(existingId, DayOfWeek.MONDAY, "09:00", "10:00"),
                        newWindow(DayOfWeek.TUESDAY, "09:00", "10:00")));

        assertThat(after).hasSize(2);
        assertThat(after).extracting(ReservationWindowResponse::id).contains(existingId);
    }

    @Test
    void retiredWindow_doesNotBlockANewOverlappingWindow() {
        User host = hostWithWeekdayAvailability();
        EventType group = groupType(host.getId());
        UUID mondayId = windowService.replaceWindows(host.getId(), group.getId(),
                List.of(newWindow(DayOfWeek.MONDAY, "09:00", "10:00"))).get(0).id();

        // Retire it by omission, then re-create the same slot on a second event type.
        windowService.replaceWindows(host.getId(), group.getId(), List.of());
        assertThat(windowRepository.findById(mondayId).orElseThrow().getStatus())
                .isEqualTo(ReservationWindowStatus.RETIRED);

        EventType second = groupType(host.getId());
        // Cross-event overlap validation must ignore retired windows, or a retired
        // rule would permanently poison that time for every other group event.
        List<ReservationWindowResponse> created = windowService.replaceWindows(
                host.getId(), second.getId(),
                List.of(newWindow(DayOfWeek.MONDAY, "09:00", "10:00")));
        assertThat(created).hasSize(1);
    }
}
