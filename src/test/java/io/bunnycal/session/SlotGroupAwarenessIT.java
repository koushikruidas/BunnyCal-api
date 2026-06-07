package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.service.SlotService;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionService;
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
 * Verifies that FULL group sessions are hidden from slot availability,
 * OPEN sessions are transparent, and ONE_ON_ONE events are unaffected.
 *
 * Session-reuse invariant: all attendees of a slot share one EventSession row
 * until confirmed_count == capacity, at which point status flips FULL and the slot
 * disappears from availability.
 */
class SlotGroupAwarenessIT extends AbstractSessionIT {

    @Autowired private SessionService sessionService;
    @Autowired private SlotService slotService;

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Monday 2026-06-15 09:00 UTC. Chosen to be well within maxAdvance. */
    private static final LocalDate TEST_DATE = LocalDate.of(2026, 6, 15);

    private Instant slotAt(int hour) {
        return TEST_DATE.atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    /** Returns a fresh host with a Monday 09:00–17:00 availability rule. */
    private User createHostWithMondayAvailability() {
        User host = createHost();
        jdbc.update("""
                INSERT INTO availability_rules
                    (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                VALUES (?, ?, 'MONDAY', '09:00', '17:00', NOW(), NOW())
                """, UUID.randomUUID(), host.getId());
        return host;
    }

    private EventType createGroupType(UUID hostId, int capacity) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId)
                .name("Group Session")
                .slug("group-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofHours(1))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofHours(1))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.GROUP)
                .capacity(capacity)
                .build());
    }

    private EventType createOneOnOneType(UUID hostId) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId)
                .name("1-on-1")
                .slug("one-on-one-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofHours(1))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofHours(1))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.ONE_ON_ONE)
                .capacity(1)
                .build());
    }

    private List<Instant> slotStarts(SlotResponse response) {
        return response.slots().stream().map(SlotDto::start).toList();
    }

    private SlotResponse getSlots(UUID userId, UUID eventTypeId) {
        return slotService.getSlots(new SlotRequest(userId, eventTypeId, TEST_DATE));
    }

    // ── core invariant: session reuse ──────────────────────────────────────────

    @Test
    void groupSlot_existingOpenSession_reused_allAttendeesShareOneSessionRow() {
        User host = createHostWithMondayAvailability();
        EventType et = createGroupType(host.getId(), 3);
        Instant start = slotAt(9);
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult joinA = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 3, "a@test.com", "A", Duration.ofMinutes(15));
        sessionService.confirmRegistration(joinA.sessionId(), joinA.registrationId(), host.getId());

        JoinSessionResult joinB = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 3, "b@test.com", "B", Duration.ofMinutes(15));
        sessionService.confirmRegistration(joinB.sessionId(), joinB.registrationId(), host.getId());

        JoinSessionResult joinC = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 3, "c@test.com", "C", Duration.ofMinutes(15));
        sessionService.confirmRegistration(joinC.sessionId(), joinC.registrationId(), host.getId());

        // All three must reference the same session.
        assertThat(joinA.sessionId()).isEqualTo(joinB.sessionId());
        assertThat(joinB.sessionId()).isEqualTo(joinC.sessionId());
        UUID sessionId = joinA.sessionId();

        // Exactly one session row for this (host, event_type, start_time).
        Integer sessionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE event_type_id = ? AND start_time = ?",
                Integer.class, et.getId(), java.sql.Timestamp.from(start));
        assertThat(sessionCount).isEqualTo(1);

        // confirmed_count = 3 == capacity → FULL.
        assertThat(querySession(sessionId).get("status")).isEqualTo("FULL");
        assertThat(((Number) querySession(sessionId).get("confirmed_count")).intValue()).isEqualTo(3);

        // All three registrations reference session X.
        assertThat(countRegistrationsByStatus(sessionId, "CONFIRMED")).isEqualTo(3);
    }

    // ── slot visibility tests ──────────────────────────────────────────────────

    @Test
    void groupSlot_noSessionYet_allSlotsVisible() {
        User host = createHostWithMondayAvailability();
        EventType et = createGroupType(host.getId(), 5);

        List<Instant> starts = slotStarts(getSlots(host.getId(), et.getId()));

        // 09:00–17:00 with 1-hour slots → 8 slots
        assertThat(starts).hasSize(8);
        assertThat(starts).contains(slotAt(9), slotAt(10), slotAt(11));
    }

    @Test
    void groupSlot_openSession_slotsStillVisible() {
        User host = createHostWithMondayAvailability();
        EventType et = createGroupType(host.getId(), 3);
        Instant start = slotAt(9);
        Instant end = start.plus(Duration.ofHours(1));

        // One attendee confirms — session becomes OPEN with confirmed_count=1.
        JoinSessionResult join = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 3, "a@test.com", "A", Duration.ofMinutes(15));
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        assertThat(querySession(join.sessionId()).get("status")).isEqualTo("OPEN");

        List<Instant> starts = slotStarts(getSlots(host.getId(), et.getId()));
        // 09:00 must still be visible (2 seats remain).
        assertThat(starts).contains(slotAt(9));
    }

    @Test
    void groupSlot_fullSession_slotHidden() {
        User host = createHostWithMondayAvailability();
        int capacity = 2;
        EventType et = createGroupType(host.getId(), capacity);
        Instant start = slotAt(9);
        Instant end = start.plus(Duration.ofHours(1));

        // Fill to capacity.
        for (int i = 0; i < capacity; i++) {
            JoinSessionResult join = sessionService.joinSession(
                    host.getId(), et.getId(), start, end, capacity,
                    "attendee-" + i + "@test.com", "A" + i, Duration.ofMinutes(15));
            sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());
        }

        assertThat(querySession(
                (UUID) jdbc.queryForObject(
                        "SELECT id FROM event_sessions WHERE event_type_id = ? AND start_time = ?",
                        UUID.class, et.getId(), java.sql.Timestamp.from(start)))
                .get("status")).isEqualTo("FULL");

        List<Instant> starts = slotStarts(getSlots(host.getId(), et.getId()));
        assertThat(starts).doesNotContain(slotAt(9));
        // Other slots on the day are still visible.
        assertThat(starts).contains(slotAt(10));
    }

    @Test
    void groupSlot_attendeeCancels_slotReappears() {
        User host = createHostWithMondayAvailability();
        int capacity = 1;
        EventType et = createGroupType(host.getId(), capacity);
        Instant start = slotAt(9);
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult join = sessionService.joinSession(
                host.getId(), et.getId(), start, end, capacity,
                "a@test.com", "A", Duration.ofMinutes(15));
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        // Slot is hidden (FULL).
        assertThat(slotStarts(getSlots(host.getId(), et.getId()))).doesNotContain(slotAt(9));

        // Attendee cancels — FULL→OPEN.
        sessionService.cancelRegistration(join.sessionId(), join.registrationId(), host.getId(), null);
        assertThat(querySession(join.sessionId()).get("status")).isEqualTo("OPEN");

        // Slot reappears.
        assertThat(slotStarts(getSlots(host.getId(), et.getId()))).contains(slotAt(9));
    }

    @Test
    void groupSlot_cancelledSession_slotReappears() {
        User host = createHostWithMondayAvailability();
        int capacity = 1;
        EventType et = createGroupType(host.getId(), capacity);
        Instant start = slotAt(9);
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult join = sessionService.joinSession(
                host.getId(), et.getId(), start, end, capacity,
                "a@test.com", "A", Duration.ofMinutes(15));
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        // Hidden (FULL).
        assertThat(slotStarts(getSlots(host.getId(), et.getId()))).doesNotContain(slotAt(9));

        // Host cancels — CANCELLED sessions are not queried.
        sessionService.cancelSession(join.sessionId(), host.getId());

        // Slot reappears.
        assertThat(slotStarts(getSlots(host.getId(), et.getId()))).contains(slotAt(9));
    }

    @Test
    void groupSlot_multipleSessions_onlyFullHidden() {
        User host = createHostWithMondayAvailability();
        int capacity = 1;
        EventType et = createGroupType(host.getId(), capacity);

        Instant start9 = slotAt(9);
        Instant start10 = slotAt(10);
        Instant end9 = start9.plus(Duration.ofHours(1));
        Instant end10 = start10.plus(Duration.ofHours(1));

        // 09:00 session → FULL.
        JoinSessionResult joinA = sessionService.joinSession(
                host.getId(), et.getId(), start9, end9, capacity,
                "a@test.com", "A", Duration.ofMinutes(15));
        sessionService.confirmRegistration(joinA.sessionId(), joinA.registrationId(), host.getId());

        // 10:00 session → OPEN (no one confirmed).
        sessionService.joinSession(
                host.getId(), et.getId(), start10, end10, capacity,
                "b@test.com", "B", Duration.ofMinutes(15));
        // (not confirmed — session remains OPEN)

        List<Instant> starts = slotStarts(getSlots(host.getId(), et.getId()));
        assertThat(starts).doesNotContain(slotAt(9));  // FULL → hidden
        assertThat(starts).contains(slotAt(10));        // OPEN → visible
    }

    @Test
    void oneOnOne_unaffectedByGroupSessions() {
        User host = createHostWithMondayAvailability();
        EventType oneOnOne = createOneOnOneType(host.getId());
        EventType group = createGroupType(host.getId(), 1);
        Instant start = slotAt(9);
        Instant end = start.plus(Duration.ofHours(1));

        // Fill the group session at 09:00 to FULL.
        JoinSessionResult join = sessionService.joinSession(
                host.getId(), group.getId(), start, end, 1,
                "g@test.com", "G", Duration.ofMinutes(15));
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        // ONE_ON_ONE event for same host/time must still show 09:00.
        List<Instant> starts = slotStarts(getSlots(host.getId(), oneOnOne.getId()));
        assertThat(starts).contains(slotAt(9));
    }
}
