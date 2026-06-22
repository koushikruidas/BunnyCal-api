package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.service.SlotService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Group Event recurrence semantics:
 * ONE_TIME, RECURRING/NONE, RECURRING/UNTIL_DATE, RECURRING/OCCURRENCE_COUNT.
 *
 * All tests use fixed future dates well within maxAdvance (365 days from now as
 * configured in createGroupType / createOneOnOneType).
 *
 * Date anchors:
 *   ANCHOR_MONDAY  = 2026-08-03 (Monday)
 *   ANCHOR_MONDAY2 = 2026-08-10 (Monday, week after)
 *   ANCHOR_MONDAY3 = 2026-08-17 (Monday, 2 weeks after)
 *   ANCHOR_MONDAY9 = 2026-09-28 (Monday, occurrence #9 from 2026-08-03)
 *   ANCHOR_FRIDAY  = 2026-08-07 (Friday, same week as ANCHOR_MONDAY)
 */
class GroupEventRecurrenceIT extends AbstractSessionIT {

    @Autowired private SlotService slotService;

    private static final LocalDate ANCHOR_MONDAY  = LocalDate.of(2026, 8, 3);
    private static final LocalDate ANCHOR_MONDAY2 = LocalDate.of(2026, 8, 10);
    private static final LocalDate ANCHOR_MONDAY3 = LocalDate.of(2026, 8, 17);
    private static final LocalDate ANCHOR_MONDAY9 = LocalDate.of(2026, 9, 28); // week index 8 from 2026-08-03
    private static final LocalDate ANCHOR_FRIDAY  = LocalDate.of(2026, 8, 7);

    private Instant slotAt(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

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

    private EventType createGroupType(UUID hostId) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId).name("Group").slug("g-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30)).bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30)).minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(15)).kind(EventKind.GROUP).capacity(20).build());
    }

    private EventType createOneOnOneType(UUID hostId) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId).name("1on1").slug("o-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30)).bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30)).minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(15)).kind(EventKind.ONE_ON_ONE).capacity(1).build());
    }

    /** ONE_TIME window on a specific date. */
    private void insertOneTimeWindow(UUID eventTypeId, LocalDate eventDate, String startTime, String endTime) {
        jdbc.update("""
                INSERT INTO group_event_reservation_windows
                    (id, event_type_id, day_of_week, start_time, end_time,
                     schedule_type, event_date, recurrence_end_mode, created_at, updated_at)
                VALUES (?, ?, NULL, ?::time, ?::time, 'ONE_TIME', ?, 'NONE', NOW(), NOW())
                """, UUID.randomUUID(), eventTypeId, startTime, endTime, eventDate);
    }

    /** RECURRING window, anchored to startDate, with no end. */
    private void insertRecurringWindow(UUID eventTypeId, String dayOfWeek,
                                       String startTime, String endTime, LocalDate startDate) {
        jdbc.update("""
                INSERT INTO group_event_reservation_windows
                    (id, event_type_id, day_of_week, start_time, end_time,
                     schedule_type, frequency, start_date, recurrence_end_mode,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?::time, ?::time, 'RECURRING', 'WEEKLY', ?, 'NONE', NOW(), NOW())
                """, UUID.randomUUID(), eventTypeId, dayOfWeek, startTime, endTime, startDate);
    }

    /** RECURRING window ending on a specific date. */
    private void insertRecurringUntilDateWindow(UUID eventTypeId, String dayOfWeek,
                                                String startTime, String endTime,
                                                LocalDate startDate, LocalDate untilDate) {
        jdbc.update("""
                INSERT INTO group_event_reservation_windows
                    (id, event_type_id, day_of_week, start_time, end_time,
                     schedule_type, frequency, start_date, recurrence_end_mode, until_date,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?::time, ?::time, 'RECURRING', 'WEEKLY', ?, 'UNTIL_DATE', ?, NOW(), NOW())
                """, UUID.randomUUID(), eventTypeId, dayOfWeek, startTime, endTime, startDate, untilDate);
    }

    /** RECURRING window limited to N occurrences. */
    private void insertRecurringOccurrenceCountWindow(UUID eventTypeId, String dayOfWeek,
                                                      String startTime, String endTime,
                                                      LocalDate startDate, int occurrenceCount) {
        jdbc.update("""
                INSERT INTO group_event_reservation_windows
                    (id, event_type_id, day_of_week, start_time, end_time,
                     schedule_type, frequency, start_date, recurrence_end_mode, occurrence_count,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?::time, ?::time, 'RECURRING', 'WEEKLY', ?, 'OCCURRENCE_COUNT', ?, NOW(), NOW())
                """, UUID.randomUUID(), eventTypeId, dayOfWeek, startTime, endTime, startDate, occurrenceCount);
    }

    private List<Instant> slotStarts(UUID hostId, UUID eventTypeId, LocalDate date) {
        SlotResponse response = slotService.getSlots(new SlotRequest(hostId, eventTypeId, date));
        return response.slots().stream().map(SlotDto::start).toList();
    }

    // ── 1. ONE_TIME — blocks other type on exact date ─────────────────────────

    @Test
    void oneTime_onMatchingDate_blocksOtherType() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId());
        EventType oneOnOne = createOneOnOneType(host.getId());
        insertOneTimeWindow(demo.getId(), ANCHOR_MONDAY, "09:00", "12:00");

        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY))
                .doesNotContain(slotAt(ANCHOR_MONDAY, 9, 30))
                .doesNotContain(slotAt(ANCHOR_MONDAY, 11, 30));
        // Time outside the window stays available.
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY))
                .contains(slotAt(ANCHOR_MONDAY, 12, 0));
    }

    // ── 2. ONE_TIME — no block on a different date ────────────────────────────

    @Test
    void oneTime_onDifferentDate_noBlock() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId());
        EventType oneOnOne = createOneOnOneType(host.getId());
        insertOneTimeWindow(demo.getId(), ANCHOR_MONDAY, "09:00", "12:00");

        // The following Monday is a different date — no block.
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY2))
                .contains(slotAt(ANCHOR_MONDAY2, 9, 30))
                .contains(slotAt(ANCHOR_MONDAY2, 11, 30));
    }

    // ── 3. ONE_TIME — owner sees window as slot source on event date ──────────

    @Test
    void oneTime_ownerSeesSlotOnEventDate() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId());
        insertOneTimeWindow(demo.getId(), ANCHOR_MONDAY, "09:00", "12:00");

        List<Instant> ownerStarts = slotStarts(host.getId(), demo.getId(), ANCHOR_MONDAY);
        assertThat(ownerStarts).contains(slotAt(ANCHOR_MONDAY, 9, 0));
        assertThat(ownerStarts).contains(slotAt(ANCHOR_MONDAY, 11, 30));
        // No slots on a different date (ONE_TIME = single occurrence).
        assertThat(slotStarts(host.getId(), demo.getId(), ANCHOR_MONDAY2)).isEmpty();
    }

    // ── 4. RECURRING/UNTIL_DATE — blocks before end date ─────────────────────

    @Test
    void recurringUntilDate_beforeEnd_blocks() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId());
        EventType oneOnOne = createOneOnOneType(host.getId());
        // Until date = ANCHOR_MONDAY2 (inclusive)
        insertRecurringUntilDateWindow(demo.getId(), "MONDAY", "09:00", "12:00",
                ANCHOR_MONDAY, ANCHOR_MONDAY2);

        // Both ANCHOR_MONDAY and ANCHOR_MONDAY2 are within range → block.
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY))
                .doesNotContain(slotAt(ANCHOR_MONDAY, 9, 30));
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY2))
                .doesNotContain(slotAt(ANCHOR_MONDAY2, 9, 30));
    }

    // ── 5. RECURRING/UNTIL_DATE — no block after end date ────────────────────

    @Test
    void recurringUntilDate_afterEnd_noBlock() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId());
        EventType oneOnOne = createOneOnOneType(host.getId());
        // Until = ANCHOR_MONDAY (inclusive); ANCHOR_MONDAY3 is after that.
        insertRecurringUntilDateWindow(demo.getId(), "MONDAY", "09:00", "12:00",
                ANCHOR_MONDAY, ANCHOR_MONDAY);

        // ANCHOR_MONDAY2 is after until_date → no block.
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY2))
                .contains(slotAt(ANCHOR_MONDAY2, 9, 30));
    }

    // ── 6. RECURRING/OCCURRENCE_COUNT — blocks within count ──────────────────

    @Test
    void recurringOccurrenceCount_withinCount_blocks() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId());
        EventType oneOnOne = createOneOnOneType(host.getId());
        // 3 occurrences: 2026-08-03, 2026-08-10, 2026-08-17
        insertRecurringOccurrenceCountWindow(demo.getId(), "MONDAY", "09:00", "12:00",
                ANCHOR_MONDAY, 3);

        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY))
                .doesNotContain(slotAt(ANCHOR_MONDAY, 9, 30));
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY2))
                .doesNotContain(slotAt(ANCHOR_MONDAY2, 9, 30));
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY3))
                .doesNotContain(slotAt(ANCHOR_MONDAY3, 9, 30));
    }

    // ── 7. RECURRING/OCCURRENCE_COUNT — boundary: Nth included, N+1 excluded ─

    @Test
    void recurringOccurrenceCount_atBoundary() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId());
        EventType oneOnOne = createOneOnOneType(host.getId());
        // 3 occurrences: 2026-08-03 (idx 0), 2026-08-10 (idx 1), 2026-08-17 (idx 2).
        // ANCHOR_MONDAY3 is idx 2 (last), week after (ANCHOR_MONDAY3+7 = 2026-08-24, idx 3) is out.
        LocalDate outsideDate = LocalDate.of(2026, 8, 24); // week index 3, outside count=3
        insertRecurringOccurrenceCountWindow(demo.getId(), "MONDAY", "09:00", "12:00",
                ANCHOR_MONDAY, 3);

        // 3rd occurrence (idx=2) still blocks.
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY3))
                .doesNotContain(slotAt(ANCHOR_MONDAY3, 9, 30));
        // 4th occurrence (idx=3) does NOT block.
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), outsideDate))
                .contains(slotAt(outsideDate, 9, 30));
    }

    // ── 8. RECURRING/NONE — blocks indefinitely (regression) ─────────────────

    @Test
    void recurringNone_blocksIndefinitely() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId());
        EventType oneOnOne = createOneOnOneType(host.getId());
        insertRecurringWindow(demo.getId(), "MONDAY", "09:00", "12:00", ANCHOR_MONDAY);

        // All three Mondays should be blocked.
        for (LocalDate monday : List.of(ANCHOR_MONDAY, ANCHOR_MONDAY2, ANCHOR_MONDAY3)) {
            assertThat(slotStarts(host.getId(), oneOnOne.getId(), monday))
                    .as("expected block on " + monday)
                    .doesNotContain(slotAt(monday, 9, 30));
        }
        // Fridays are not blocked (different day-of-week).
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_FRIDAY))
                .contains(slotAt(ANCHOR_FRIDAY, 9, 30));
    }

    // ── 9. RECURRING — before startDate has no effect ────────────────────────

    @Test
    void recurringStartDate_beforeStart_noBlock() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId());
        EventType oneOnOne = createOneOnOneType(host.getId());
        // startDate = ANCHOR_MONDAY2; query ANCHOR_MONDAY (one week earlier).
        insertRecurringWindow(demo.getId(), "MONDAY", "09:00", "12:00", ANCHOR_MONDAY2);

        // ANCHOR_MONDAY is before startDate → no block.
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY))
                .contains(slotAt(ANCHOR_MONDAY, 9, 30));
        // ANCHOR_MONDAY2 is on startDate → block.
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY2))
                .doesNotContain(slotAt(ANCHOR_MONDAY2, 9, 30));
    }

    // ── 10. OCCURRENCE_COUNT — date before startDate is not blocked ───────────

    @Test
    void occurrenceCount_queryBeforeStartDate_noBlock() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId());
        EventType oneOnOne = createOneOnOneType(host.getId());
        // 8 occurrences starting from ANCHOR_MONDAY2.
        insertRecurringOccurrenceCountWindow(demo.getId(), "MONDAY", "09:00", "12:00",
                ANCHOR_MONDAY2, 8);

        // ANCHOR_MONDAY is one week before startDate → weekIndex would be negative → no block.
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY))
                .contains(slotAt(ANCHOR_MONDAY, 9, 30));
        // ANCHOR_MONDAY2 = startDate (weekIndex=0, occurrence #1) → blocked.
        assertThat(slotStarts(host.getId(), oneOnOne.getId(), ANCHOR_MONDAY2))
                .doesNotContain(slotAt(ANCHOR_MONDAY2, 9, 30));
    }
}
