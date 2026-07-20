package io.bunnycal.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.service.SlotService;
import io.bunnycal.session.service.SessionService;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Rescheduling one occurrence of a recurring group event.
 *
 * <p>Two facts are deliberately separate here, and most of these tests exist to keep them apart:
 *
 * <ul>
 *   <li><b>Occurrence consumption</b> — unconditional. Once an occurrence moves, its own rule
 *       never regenerates it. Moving this week's class does not schedule a second one.</li>
 *   <li><b>Cross-event availability</b> — the host's choice. Whether the vacated hour opens up
 *       for their <em>other</em> event types.</li>
 * </ul>
 */
class SessionOriginHoldIT extends AbstractSessionIT {

    @Autowired SessionService sessionService;
    @Autowired SlotService slotService;

    private static final LocalDate TEST_DATE = nextMonday(7);

    private static LocalDate nextMonday(int minDaysFromNow) {
        LocalDate base = LocalDate.now().plusDays(minDaysFromNow);
        int shift = (DayOfWeek.MONDAY.getValue() - base.getDayOfWeek().getValue() + 7) % 7;
        return base.plusDays(shift);
    }

    @BeforeEach
    void cleanExtra() {
        jdbc.execute("TRUNCATE TABLE bookings, availability_rules CASCADE");
    }

    // ── Cross-event availability ─────────────────────────────────────────────

    @Test
    @DisplayName("held origin blocks every other event type")
    void heldOrigin_blocksAllOtherEventTypes() {
        User host = createHostWithAvailability();
        EventType group = createGroupWithWindow(host.getId());
        // 11:00 deliberately: the reservation window covers 09:00-10:00 and blocks other event
        // types from its configuration alone, which would mask the origin hold entirely. Only an
        // origin outside the window isolates the behaviour under test.
        Instant originalStart = TEST_DATE.atTime(11, 0).toInstant(ZoneOffset.UTC);
        UUID sessionId = bookAndMaterialize(host, group, originalStart);

        sessionService.rescheduleSession(sessionId, host.getId(),
                TEST_DATE.atTime(14, 0).toInstant(ZoneOffset.UTC));

        // The vacated 11:00-12:00 must be unavailable to everything the host owns. A blocker that
        // exempted any one kind would let the host be double-booked at a time they said they
        // could not make.
        for (EventKind kind : List.of(EventKind.ONE_ON_ONE, EventKind.ROUND_ROBIN, EventKind.COLLECTIVE)) {
            EventType other = createDemandDrivenEventType(host.getId(), kind);
            assertThat(slotStarts(host.getId(), other.getId()))
                    .as("%s must not be offered the vacated hour", kind)
                    .doesNotContain(originalStart);
        }

        // Including a different GROUP event type, which reaches the blocker by another path.
        EventType otherGroup = createGroupWithWindow(host.getId());
        assertThat(slotStarts(host.getId(), otherGroup.getId()))
                .as("a second group event must not be offered the vacated hour")
                .doesNotContain(originalStart);
    }

    /**
     * The hold is unconditional — there is no host option to release it.
     *
     * <p>Releasing was designed and withdrawn before shipping. The recurring window reserves the
     * vacated hour from its own configuration, with no session involved, so clearing a session's
     * hold cannot free a slot the rule still covers. The control would have worked only when the
     * origin happened to fall outside its own window — indistinguishable, to a host, from a
     * control that does nothing. This test pins the absence so a future release option has to
     * confront the window rule rather than reintroduce a flag that silently no-ops.
     */
    @Test
    @DisplayName("the vacated hour is held with no way to release it")
    void originHoldIsUnconditional() {
        User host = createHostWithAvailability();
        EventType group = createGroupWithWindow(host.getId());
        // Off-window, so the reservation window cannot stand in for the hold under test.
        Instant offWindowOrigin = TEST_DATE.atTime(11, 0).toInstant(ZoneOffset.UTC);
        UUID sessionId = bookAndMaterialize(host, group, offWindowOrigin);

        sessionService.rescheduleSession(sessionId, host.getId(),
                TEST_DATE.atTime(14, 0).toInstant(ZoneOffset.UTC));

        assertThat(jdbc.queryForObject(
                "SELECT origin_blocks_other_events FROM event_sessions WHERE id = ?",
                Boolean.class, sessionId))
                .isTrue();

        EventType oneOnOne = createDemandDrivenEventType(host.getId(), EventKind.ONE_ON_ONE);
        assertThat(slotStarts(host.getId(), oneOnOne.getId()))
                .as("the vacated hour stays blocked")
                .doesNotContain(offWindowOrigin);
    }

    // ── Occurrence consumption ───────────────────────────────────────────────

    @Test
    @DisplayName("the moved occurrence is never regenerated by its own rule")
    void movedOccurrence_isNotRegenerated() {
        User host = createHostWithAvailability();
        EventType group = createGroupWithWindow(host.getId());
        Instant originalStart = TEST_DATE.atTime(9, 0).toInstant(ZoneOffset.UTC);
        UUID sessionId = bookAndMaterialize(host, group, originalStart);

        sessionService.rescheduleSession(sessionId, host.getId(),
                TEST_DATE.atTime(14, 0).toInstant(ZoneOffset.UTC));

        // Consumption is separate from the hold: the rule must not put a second copy of this
        // class back on the old day, whatever the hour's availability to other events.
        assertThat(slotStarts(host.getId(), group.getId()))
                .as("the owning event consumed this occurrence by moving it")
                .doesNotContain(originalStart);
    }

    // ── Lineage ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the hold follows the origin, not intermediate positions")
    void movingTwice_holdsOnlyTheOriginalOccurrence() {
        User host = createHostWithAvailability();
        EventType group = createGroupWithWindow(host.getId());
        // Off-window, isolating the hold from the reservation window.
        Instant originalStart = TEST_DATE.atTime(11, 0).toInstant(ZoneOffset.UTC);
        UUID sessionId = bookAndMaterialize(host, group, originalStart);

        sessionService.rescheduleSession(sessionId, host.getId(),
                TEST_DATE.atTime(14, 0).toInstant(ZoneOffset.UTC));
        sessionService.rescheduleSession(sessionId, host.getId(),
                TEST_DATE.atTime(15, 0).toInstant(ZoneOffset.UTC));

        // Lineage still points at the original occurrence, so the hold applies there — not to the
        // intermediate 14:00, which was never an occurrence of anything.
        EventType oneOnOne = createDemandDrivenEventType(host.getId(), EventKind.ONE_ON_ONE);
        List<Instant> starts = slotStarts(host.getId(), oneOnOne.getId());
        assertThat(starts).as("still held after a second move").doesNotContain(originalStart);
        assertThat(starts).as("14:00 was never an origin").contains(TEST_DATE.atTime(14, 0).toInstant(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("moving a session back to its origin leaves no ghost and no duplicate")
    void movingBackToOrigin_leavesExactlyOneSession() {
        User host = createHostWithAvailability();
        EventType group = createGroupWithWindow(host.getId());
        // Off-window, isolating the hold from the reservation window.
        Instant originalStart = TEST_DATE.atTime(11, 0).toInstant(ZoneOffset.UTC);
        UUID sessionId = bookAndMaterialize(host, group, originalStart);

        sessionService.rescheduleSession(sessionId, host.getId(),
                TEST_DATE.atTime(14, 0).toInstant(ZoneOffset.UTC));
        sessionService.rescheduleSession(sessionId, host.getId(), originalStart);

        Integer sessionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE event_type_id = ?",
                Integer.class, group.getId());
        assertThat(sessionCount).as("the move-back must not materialize a second row").isEqualTo(1);

        // start_time == scheduled_occurrence_start again, so the session is no longer moved and
        // stops holding anything: the origin blocker and the session blocker would otherwise be
        // the same hour counted twice.
        assertThat(jdbc.queryForObject(
                "SELECT start_time = scheduled_occurrence_start FROM event_sessions WHERE id = ?",
                Boolean.class, sessionId))
                .isTrue();

        EventType oneOnOne = createDemandDrivenEventType(host.getId(), EventKind.ONE_ON_ONE);
        assertThat(slotStarts(host.getId(), oneOnOne.getId()))
                .as("the session itself now occupies the hour, so it stays blocked")
                .doesNotContain(originalStart);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Instant> slotStarts(UUID hostId, UUID eventTypeId) {
        return slotService.getSlots(new SlotRequest(hostId, eventTypeId, TEST_DATE))
                .slots().stream().map(SlotDto::start).toList();
    }

    private UUID bookAndMaterialize(User host, EventType eventType, Instant start) {
        jdbc.update("""
                INSERT INTO event_sessions
                    (id, host_id, event_type_id, start_time, end_time, capacity, confirmed_count,
                     status, version, calendar_sequence, scheduled_occurrence_start, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 10, 1, 'OPEN', 0, 0, ?, NOW(), NOW())
                """,
                UUID.randomUUID(), host.getId(), eventType.getId(),
                java.sql.Timestamp.from(start),
                java.sql.Timestamp.from(start.plus(Duration.ofHours(1))),
                java.sql.Timestamp.from(start));
        return jdbc.queryForObject(
                "SELECT id FROM event_sessions WHERE event_type_id = ? AND start_time = ?",
                UUID.class, eventType.getId(), java.sql.Timestamp.from(start));
    }

    private User createHostWithAvailability() {
        User host = createHost();
        jdbc.update("""
                INSERT INTO availability_rules
                    (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                VALUES (?, ?, 'MONDAY', '08:00', '18:00', NOW(), NOW())
                """, UUID.randomUUID(), host.getId());
        return host;
    }

    private EventType createGroupWithWindow(UUID hostId) {
        EventType eventType = createGroupEventType(hostId, 10);
        jdbc.update("""
                INSERT INTO group_event_reservation_windows
                    (id, event_type_id, day_of_week, start_time, end_time,
                     schedule_type, frequency, start_date, recurrence_end_mode, created_at, updated_at)
                VALUES (?, ?, 'MONDAY', '09:00'::time, '10:00'::time,
                        'RECURRING', 'WEEKLY', '2000-01-01', 'NONE', NOW(), NOW())
                """, UUID.randomUUID(), eventType.getId());
        return eventType;
    }

    private EventType createDemandDrivenEventType(UUID hostId, EventKind kind) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId)
                .name(kind + " event")
                .slug("evt-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofHours(1))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofHours(1))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .kind(kind)
                .capacity(1)
                .published(true)
                .build());
    }
}
