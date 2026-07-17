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
 * Group Event Reservation Windows.
 *
 * A GROUP event type may reserve recurring weekly windows (e.g. every Wednesday
 * 09:00-11:00). The reservation comes from the configuration alone -- before any
 * booking, session, registration, or calendar event exists.
 *
 * Ownership semantics:
 *  - the owning event type still sees the window (it is its slot source);
 *  - every OTHER event type of the same host is blocked during the window;
 *  - once a session inside the window fills to capacity (FULL), the owning type is
 *    blocked too (existing session-capacity behavior, unchanged).
 */
class GroupEventReservationWindowIT extends AbstractSessionIT {

    @Autowired private SlotService slotService;
    @Autowired private SessionService sessionService;

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 5);  // Wednesday
    private static final LocalDate THURSDAY  = LocalDate.of(2026, 8, 6);  // Thursday
    private static final LocalDate FRIDAY    = LocalDate.of(2026, 8, 7);  // Friday

    private Instant slotAt(int hour, int minute) {
        return WEDNESDAY.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    private Instant slotAt(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    /** Host available Mon-Fri 09:00-17:00 (global availability, unchanged model). */
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

    private EventType createGroupType(UUID hostId, int capacity) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId)
                .name("Weekly Product Demo")
                .slug("demo-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
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
                .name("One-to-One")
                .slug("1on1-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.ONE_ON_ONE)
                .capacity(1)
                .build());
    }

    /** Creates a demand-driven event type of the given kind (capacity 1). */
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
                .capacity(1)
                .build());
    }

    /** Reserves WEDNESDAY [start,end) for the given event type. */
    private void reserveWednesday(UUID eventTypeId, String startTime, String endTime) {
        reserveDay(eventTypeId, "WEDNESDAY", startTime, endTime);
    }

    private void reserveFriday(UUID eventTypeId, String startTime, String endTime) {
        reserveDay(eventTypeId, "FRIDAY", startTime, endTime);
    }

    private void reserveDay(UUID eventTypeId, String dayOfWeek, String startTime, String endTime) {
        jdbc.update("""
                INSERT INTO group_event_reservation_windows
                    (id, event_type_id, day_of_week, start_time, end_time,
                     schedule_type, frequency, start_date, recurrence_end_mode,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?::time, ?::time, 'RECURRING', 'WEEKLY', '2000-01-01', 'NONE', NOW(), NOW())
                """, UUID.randomUUID(), eventTypeId, dayOfWeek, startTime, endTime);
    }

    private List<Instant> slotStarts(UUID hostId, UUID eventTypeId) {
        return slotStarts(hostId, eventTypeId, WEDNESDAY);
    }

    private List<Instant> slotStarts(UUID hostId, UUID eventTypeId, LocalDate date) {
        SlotResponse response = slotService.getSlots(new SlotRequest(hostId, eventTypeId, date));
        return response.slots().stream().map(SlotDto::start).toList();
    }

    // ── Requested regression: GROUP uses reservation windows as slot source ───

    @Test
    void groupReservation_fridayOnly_thursdayReturnsZeroSlots() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 20);
        reserveFriday(demo.getId(), "09:00", "12:00");

        assertThat(slotStarts(host.getId(), demo.getId(), THURSDAY)).isEmpty();
    }

    @Test
    void groupReservation_fridayOnly_fridaySlotsStayInsideWindow() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 20);
        reserveFriday(demo.getId(), "09:00", "12:00");

        List<Instant> starts = slotStarts(host.getId(), demo.getId(), FRIDAY);

        assertThat(starts).contains(
                slotAt(FRIDAY, 9, 30),
                slotAt(FRIDAY, 11, 30));
        assertThat(starts).doesNotContain(
                slotAt(FRIDAY, 8, 30),
                slotAt(FRIDAY, 12, 0),
                slotAt(FRIDAY, 13, 0),
                slotAt(FRIDAY, 14, 0),
                slotAt(FRIDAY, 15, 0));
        assertThat(starts).allSatisfy(start -> assertThat(start)
                .isBetween(slotAt(FRIDAY, 9, 0), slotAt(FRIDAY, 11, 30)));
    }

    @Test
    void oneOnOneWithoutGroupReservation_stillUsesHostAvailability() {
        User host = createHostWithWeekdayAvailability();
        EventType oneOnOne = createOneOnOneType(host.getId());

        assertThat(slotStarts(host.getId(), oneOnOne.getId(), FRIDAY))
                .contains(slotAt(FRIDAY, 9, 0), slotAt(FRIDAY, 13, 0), slotAt(FRIDAY, 15, 0));
    }

    @Test
    void groupReservation_calendarBusyInsideWindowIsRemoved() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 20);
        reserveFriday(demo.getId(), "09:00", "12:00");
        UUID connectionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO calendar_connections
                    (id, user_id, provider, provider_user_id, refresh_token_ciphertext,
                     last_token_expires_at, scopes, status, version, created_at, updated_at)
                VALUES (?, ?, 'GOOGLE', 'provider-user', 'ciphertext',
                        NOW() + INTERVAL '1 hour', ARRAY['calendar.read']::text[], 'ACTIVE', 0, NOW(), NOW())
                """, connectionId, host.getId());
        jdbc.update("""
                INSERT INTO calendar_connection_calendars
                    (id, connection_id, external_calendar_id, name, is_primary, calendar_role,
                     is_selected, checks_availability, can_read, can_write, hidden)
                VALUES (?, ?, 'primary', 'Primary', true, 'PRIMARY', true, true, true, true, false)
                """, UUID.randomUUID(), connectionId);
        jdbc.update("""
                INSERT INTO calendar_events
                    (id, user_id, connection_id, provider, external_event_id, starts_at, ends_at,
                     cancelled, deleted, blocks_availability, created_at, updated_at)
                VALUES (?, ?, ?, 'GOOGLE', 'busy-1', ?, ?, false, false, true, NOW(), NOW())
                """,
                UUID.randomUUID(),
                host.getId(),
                connectionId,
                java.sql.Timestamp.from(slotAt(FRIDAY, 10, 0)),
                java.sql.Timestamp.from(slotAt(FRIDAY, 10, 30)));

        List<Instant> starts = slotStarts(host.getId(), demo.getId(), FRIDAY);

        assertThat(starts).doesNotContain(slotAt(FRIDAY, 10, 0));
        assertThat(starts).contains(slotAt(FRIDAY, 9, 30), slotAt(FRIDAY, 10, 30), slotAt(FRIDAY, 11, 30));
    }

    // ── A: reservation visible to owning event type ──────────────────────────

    @Test
    void reservationWindow_visibleToOwningEventType() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 20);
        reserveWednesday(demo.getId(), "09:00", "11:00");

        List<Instant> starts = slotStarts(host.getId(), demo.getId());
        // 09:30 (and the rest of the reserved window) remain visible to the owner.
        assertThat(starts).contains(slotAt(9, 30));
        assertThat(starts).contains(slotAt(9, 0), slotAt(10, 30));
    }

    // ── B: reservation hides slot from a ONE_ON_ONE event type ───────────────

    @Test
    void reservationWindow_hidesSlotFromOneOnOne() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 20);
        EventType oneOnOne = createOneOnOneType(host.getId());
        reserveWednesday(demo.getId(), "09:00", "11:00");

        List<Instant> oneOnOneStarts = slotStarts(host.getId(), oneOnOne.getId());
        // The whole reserved window is blocked for the other event type...
        assertThat(oneOnOneStarts).doesNotContain(slotAt(9, 30));
        assertThat(oneOnOneStarts).doesNotContain(slotAt(9, 0), slotAt(10, 30));
        // ...but time outside the window is still bookable.
        assertThat(oneOnOneStarts).contains(slotAt(11, 0), slotAt(14, 0));
    }

    // ── C: reservation hides slot from a different GROUP event type ──────────

    @Test
    void reservationWindow_hidesSlotFromDifferentGroupEventType() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 20);
        EventType otherGroup = createGroupType(host.getId(), 20);
        reserveWednesday(demo.getId(), "09:00", "11:00");

        assertThat(slotStarts(host.getId(), demo.getId())).contains(slotAt(9, 30));
        assertThat(slotStarts(host.getId(), otherGroup.getId())).doesNotContain(slotAt(9, 30));
    }

    // ── D: enforced with zero bookings/sessions/registrations ────────────────

    @Test
    void reservationWindow_enforcedWithNoBookingsOrSessions() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 20);
        EventType oneOnOne = createOneOnOneType(host.getId());
        reserveWednesday(demo.getId(), "09:00", "11:00");

        // Prove there is no session/registration row for this window.
        Integer sessionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE event_type_id = ?", Integer.class, demo.getId());
        assertThat(sessionCount).isZero();

        // Reservation still blocks the other event type.
        assertThat(slotStarts(host.getId(), oneOnOne.getId())).doesNotContain(slotAt(9, 30));
        // And remains visible to the owner.
        assertThat(slotStarts(host.getId(), demo.getId())).contains(slotAt(9, 30));
    }

    // ── E: OPEN session inside reserved window — existing behavior preserved ──

    @Test
    void reservationWindow_openSession_ownerVisibleOthersBlocked() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 3);
        EventType oneOnOne = createOneOnOneType(host.getId());
        reserveWednesday(demo.getId(), "09:00", "11:00");

        Instant start = slotAt(9, 30);
        Instant end = start.plus(Duration.ofMinutes(30));
        JoinSessionResult join = sessionService.joinSession(
                host.getId(), demo.getId(), start, end, 3, "a@test.com", "A", Duration.ofMinutes(15));
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        assertThat(querySession(join.sessionId()).get("status")).isEqualTo("OPEN");

        // Owner still sees 09:30 (2 seats remain); other type still blocked.
        assertThat(slotStarts(host.getId(), demo.getId())).contains(slotAt(9, 30));
        assertThat(slotStarts(host.getId(), oneOnOne.getId())).doesNotContain(slotAt(9, 30));
    }

    // ── F: FULL session inside reserved window hides slot from owner too ──────

    @Test
    void reservationWindow_fullSession_hiddenFromOwner() {
        User host = createHostWithWeekdayAvailability();
        int capacity = 2;
        EventType demo = createGroupType(host.getId(), capacity);
        EventType oneOnOne = createOneOnOneType(host.getId());
        reserveWednesday(demo.getId(), "09:00", "11:00");

        Instant start = slotAt(9, 30);
        Instant end = start.plus(Duration.ofMinutes(30));
        for (int i = 0; i < capacity; i++) {
            JoinSessionResult join = sessionService.joinSession(
                    host.getId(), demo.getId(), start, end, capacity,
                    "a" + i + "@test.com", "A" + i, Duration.ofMinutes(15));
            sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());
        }

        UUID sessionId = (UUID) jdbc.queryForObject(
                "SELECT id FROM event_sessions WHERE event_type_id = ? AND start_time = ?",
                UUID.class, demo.getId(), java.sql.Timestamp.from(start));
        assertThat(querySession(sessionId).get("status")).isEqualTo("FULL");

        // FULL → 09:30 hidden even from the owner; other slots in the window still
        // visible to the owner (reservation keeps them owned), still hidden for others.
        assertThat(slotStarts(host.getId(), demo.getId())).doesNotContain(slotAt(9, 30));
        assertThat(slotStarts(host.getId(), demo.getId())).contains(slotAt(10, 0));
        assertThat(slotStarts(host.getId(), oneOnOne.getId())).doesNotContain(slotAt(9, 30), slotAt(10, 0));
    }

    // ── G: reservation only applies to its configured day-of-week ────────────

    @Test
    void reservationWindow_onlyAffectsConfiguredDayOfWeek() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 20);
        EventType oneOnOne = createOneOnOneType(host.getId());
        reserveWednesday(demo.getId(), "09:00", "11:00");

        // Thursday is not reserved → one-to-one sees 09:30 on Thursday.
        LocalDate thursday = WEDNESDAY.plusDays(1);
        List<Instant> thursdayStarts = slotService
                .getSlots(new SlotRequest(host.getId(), oneOnOne.getId(), thursday))
                .slots().stream().map(SlotDto::start).toList();
        Instant thursday0930 = thursday.atTime(9, 30).toInstant(ZoneOffset.UTC);
        assertThat(thursdayStarts).contains(thursday0930);
    }

    // ── H: a host's reservation does not leak across hosts ───────────────────

    @Test
    void reservationWindow_isScopedToOwningHost() {
        User hostA = createHostWithWeekdayAvailability();
        EventType demoA = createGroupType(hostA.getId(), 20);
        reserveWednesday(demoA.getId(), "09:00", "11:00");

        User hostB = createHostWithWeekdayAvailability();
        EventType oneOnOneB = createOneOnOneType(hostB.getId());

        // Host B's event type is unaffected by host A's reservation.
        assertThat(slotStarts(hostB.getId(), oneOnOneB.getId())).contains(slotAt(9, 30));
    }

    // ── C/D: reservation blocks Round Robin and Collective too ───────────────

    @Test
    void reservationWindow_hidesSlotFromRoundRobin() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 20);
        EventType roundRobin = createDemandDrivenType(host.getId(), EventKind.ROUND_ROBIN);
        reserveWednesday(demo.getId(), "09:00", "11:00");

        assertThat(slotStarts(host.getId(), demo.getId())).contains(slotAt(9, 30));
        assertThat(slotStarts(host.getId(), roundRobin.getId())).doesNotContain(slotAt(9, 30));
        // Time outside the reserved window stays bookable for the demand-driven type.
        assertThat(slotStarts(host.getId(), roundRobin.getId())).contains(slotAt(11, 0));
    }

    @Test
    void reservationWindow_hidesSlotFromCollective() {
        User host = createHostWithWeekdayAvailability();
        EventType demo = createGroupType(host.getId(), 20);
        EventType collective = createDemandDrivenType(host.getId(), EventKind.COLLECTIVE);
        reserveWednesday(demo.getId(), "09:00", "11:00");

        assertThat(slotStarts(host.getId(), demo.getId())).contains(slotAt(9, 30));
        assertThat(slotStarts(host.getId(), collective.getId())).doesNotContain(slotAt(9, 30));
        assertThat(slotStarts(host.getId(), collective.getId())).contains(slotAt(11, 0));
    }

    // ── I/J/K: demand-driven types reserve NOTHING themselves ────────────────

    // A One-to-One / Round Robin / Collective event type, with NO group reservation
    // anywhere on the host, must show its full global availability -- its mere
    // existence reserves no time (demand-driven). Two such types coexist on the same
    // slots until an actual booking occurs.
    @Test
    void demandDrivenTypes_reserveNothing_fullAvailabilityVisible() {
        User host = createHostWithWeekdayAvailability();
        EventType oneOnOne = createOneOnOneType(host.getId());
        EventType roundRobin = createDemandDrivenType(host.getId(), EventKind.ROUND_ROBIN);
        EventType collective = createDemandDrivenType(host.getId(), EventKind.COLLECTIVE);

        // No reservation windows exist for this host at all.
        Integer windowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_event_reservation_windows w "
                        + "JOIN event_types et ON et.id = w.event_type_id WHERE et.user_id = ?",
                Integer.class, host.getId());
        assertThat(windowCount).isZero();

        // 09:00-17:00 with 30-min interval/duration => 16 slots, identical for all three.
        List<Instant> oneOnOneStarts = slotStarts(host.getId(), oneOnOne.getId());
        List<Instant> roundRobinStarts = slotStarts(host.getId(), roundRobin.getId());
        List<Instant> collectiveStarts = slotStarts(host.getId(), collective.getId());

        assertThat(oneOnOneStarts).contains(slotAt(9, 0), slotAt(9, 30), slotAt(16, 30));
        assertThat(roundRobinStarts).isEqualTo(oneOnOneStarts);
        assertThat(collectiveStarts).isEqualTo(oneOnOneStarts);
    }
}
