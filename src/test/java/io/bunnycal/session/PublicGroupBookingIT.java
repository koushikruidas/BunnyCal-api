package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.dto.PublicBookingStatusResponse;
import io.bunnycal.booking.dto.PublicConfirmResponse;
import io.bunnycal.booking.dto.PublicHoldResponse;
import io.bunnycal.booking.dto.PublicManageBookingResponse;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the public booking flow when event type is GROUP.
 * Exercises PublicBookingService with a real Spring context + TestContainers DB.
 * Covers: hold→confirm→cancel lifecycle, capacity enforcement, idempotency,
 * reschedule rejection, slot visibility, and bypass of the calendar-conflict check.
 */
class PublicGroupBookingIT extends AbstractSessionIT {

    @Autowired private PublicBookingService publicBookingService;

    // Next Monday ≥7 days from today — always within maxAdvance=30 days.
    private static final LocalDate TEST_DATE = nextMonday(7);

    private static LocalDate nextMonday(int minDaysFromNow) {
        LocalDate base = LocalDate.now().plusDays(minDaysFromNow);
        int daysUntilMonday = (DayOfWeek.MONDAY.getValue() - base.getDayOfWeek().getValue() + 7) % 7;
        return base.plusDays(daysUntilMonday);
    }

    @BeforeEach
    void cleanExtra() {
        // Also truncate bookings and availability_rules which AbstractSessionIT skips.
        jdbc.execute("TRUNCATE TABLE bookings, availability_rules CASCADE");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Instant slotAt(int hour) {
        return TEST_DATE.atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    private User createHostWithAvailability() {
        User host = createHost();
        jdbc.update("""
                INSERT INTO availability_rules
                    (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                VALUES (?, ?, 'MONDAY', '09:00', '17:00', NOW(), NOW())
                """, UUID.randomUUID(), host.getId());
        return host;
    }

    private EventType createGroupType(UUID hostId, int capacity) {
        EventType et = createGroupEventType(hostId, capacity);
        jdbc.update("""
                INSERT INTO group_event_reservation_windows
                    (id, event_type_id, day_of_week, start_time, end_time,
                     schedule_type, frequency, start_date, recurrence_end_mode,
                     created_at, updated_at)
                VALUES (?, ?, 'MONDAY', '09:00'::time, '17:00'::time,
                        'RECURRING', 'WEEKLY', '2000-01-01', 'NONE', NOW(), NOW())
                """, UUID.randomUUID(), et.getId());
        return et;
    }

    private PublicHoldResponse hold(String username, String slug, Instant start, String email, String name) {
        return publicBookingService.hold(username, slug, new PublicBookRequest(start, email, name));
    }

    private PublicConfirmResponse confirm(String username, String slug, UUID bookingId) {
        return publicBookingService.confirm(username, slug, bookingId);
    }

    private PublicBookingStatusResponse cancel(String username, String slug, UUID bookingId, String token) {
        return publicBookingService.cancel(username, slug, bookingId, token);
    }

    // ── hold ──────────────────────────────────────────────────────────────────

    @Test
    void groupHold_returnsRegistrationIdAndSessionId() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 3);

        PublicHoldResponse response = hold(host.getUsername(), et.getSlug(),
                slotAt(9), "a@test.com", "Alice");

        assertThat(response.bookingId()).isNotNull();   // registrationId
        assertThat(response.sessionId()).isNotNull();   // sessionId — non-null for GROUP
        assertThat(response.startTime()).isEqualTo(slotAt(9));
        assertThat(response.endTime()).isEqualTo(slotAt(9).plus(Duration.ofHours(1)));
        assertThat(response.expiresAt()).isNotNull();

        // Exactly one session row was created.
        Integer sessionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE event_type_id = ?",
                Integer.class, et.getId());
        assertThat(sessionCount).isEqualTo(1);

        // Registration is PENDING.
        assertThat(queryRegistration(response.bookingId()).get("status")).isEqualTo("PENDING");
    }

    @Test
    void groupHold_multipleAttendeesShareOneSessionRow() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 3);

        PublicHoldResponse r1 = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");
        PublicHoldResponse r2 = hold(host.getUsername(), et.getSlug(), slotAt(9), "b@test.com", "B");

        assertThat(r1.sessionId()).isEqualTo(r2.sessionId());

        Integer sessionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE event_type_id = ?",
                Integer.class, et.getId());
        assertThat(sessionCount).isEqualTo(1);
    }

    // ── confirm ───────────────────────────────────────────────────────────────

    @Test
    void groupConfirm_returnsManageTokenAndSyncingStatus() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 3);
        PublicHoldResponse hold = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");

        PublicConfirmResponse response = confirm(host.getUsername(), et.getSlug(), hold.bookingId());

        assertThat(response.status()).isEqualTo("SYNCING");
        assertThat(response.manageToken()).isNotNull().isNotBlank();
        assertThat(response.sessionId()).isEqualTo(hold.sessionId());
        assertThat(response.bookingId()).isEqualTo(hold.bookingId());

        assertThat(queryRegistration(hold.bookingId()).get("status")).isEqualTo("CONFIRMED");
        assertThat(((Number) querySession(hold.sessionId()).get("confirmed_count")).intValue()).isEqualTo(1);
    }

    @Test
    void groupManageView_usesRegistrationAndSessionInsteadOfBookingsTable() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 3);
        PublicHoldResponse hold = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "Alice");
        PublicConfirmResponse confirmation = confirm(host.getUsername(), et.getSlug(), hold.bookingId());

        PublicManageBookingResponse response = publicBookingService.manageView(
                host.getUsername(), et.getSlug(), hold.bookingId(), confirmation.manageToken());

        assertThat(response.bookingId()).isEqualTo(hold.bookingId());
        assertThat(response.eventKind()).isEqualTo(io.bunnycal.availability.domain.EventKind.GROUP);
        assertThat(response.eventTitle()).isEqualTo(et.getName());
        assertThat(response.startTime()).isEqualTo(slotAt(9));
        assertThat(response.endTime()).isEqualTo(slotAt(9).plus(Duration.ofHours(1)));
        assertThat(response.attendeeName()).isEqualTo("Alice");
        assertThat(response.attendeeEmail()).isEqualTo("a@test.com");
        assertThat(response.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void groupManageView_rejectsInvalidRegistrationToken() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 3);
        PublicHoldResponse hold = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "Alice");
        confirm(host.getUsername(), et.getSlug(), hold.bookingId());

        assertThatThrownBy(() -> publicBookingService.manageView(
                host.getUsername(), et.getSlug(), hold.bookingId(), "invalid-token"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void groupConfirm_doesNotInvokeCalendarConflictCheck() {
        // The CalendarBusyTimeService is not mocked — any real call would either
        // return empty (no calendar connected) or fail. Either way, confirm must
        // succeed purely on session occupancy without touching calendar projections.
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 3);
        PublicHoldResponse hold = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");

        // Should succeed without any calendar data for this host.
        PublicConfirmResponse response = confirm(host.getUsername(), et.getSlug(), hold.bookingId());
        assertThat(response.status()).isEqualTo("SYNCING");
        assertThat(queryRegistration(hold.bookingId()).get("status")).isEqualTo("CONFIRMED");
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void groupCancel_withValidToken_cancelsRegistrationAndDecrementsCount() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 3);
        PublicHoldResponse hold = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");
        PublicConfirmResponse conf = confirm(host.getUsername(), et.getSlug(), hold.bookingId());

        assertThat(((Number) querySession(hold.sessionId()).get("confirmed_count")).intValue()).isEqualTo(1);

        PublicBookingStatusResponse cancelResp = cancel(
                host.getUsername(), et.getSlug(), hold.bookingId(), conf.manageToken());
        assertThat(cancelResp.status()).isEqualTo("CANCELLED");

        assertThat(queryRegistration(hold.bookingId()).get("status")).isEqualTo("CANCELLED");
        assertThat(((Number) querySession(hold.sessionId()).get("confirmed_count")).intValue()).isEqualTo(0);
    }

    @Test
    void groupCancel_pendingRegistration_noCountChange() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 3);
        PublicHoldResponse hold = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");

        // Cancel without token (PENDING hold has no token yet; pass null).
        cancel(host.getUsername(), et.getSlug(), hold.bookingId(), null);

        assertThat(queryRegistration(hold.bookingId()).get("status")).isEqualTo("CANCELLED");
        assertThat(((Number) querySession(hold.sessionId()).get("confirmed_count")).intValue()).isEqualTo(0);
    }

    // ── capacity enforcement ──────────────────────────────────────────────────

    @Test
    void groupHold_allowsMultiplePendingBeyondConfirmedCapacity() {
        // PENDING does not consume a seat; multiple attendees can hold simultaneously.
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 1);

        PublicHoldResponse r1 = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");
        PublicHoldResponse r2 = hold(host.getUsername(), et.getSlug(), slotAt(9), "b@test.com", "B");

        assertThat(r1.bookingId()).isNotEqualTo(r2.bookingId());
        assertThat(r1.sessionId()).isEqualTo(r2.sessionId());

        assertThat(queryRegistration(r1.bookingId()).get("status")).isEqualTo("PENDING");
        assertThat(queryRegistration(r2.bookingId()).get("status")).isEqualTo("PENDING");
        assertThat(((Number) querySession(r1.sessionId()).get("confirmed_count")).intValue()).isEqualTo(0);
    }

    @Test
    void groupConfirm_enforcesCapacity_secondConfirmFails() {
        // Capacity = 1; first confirm succeeds, second is rejected.
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 1);

        PublicHoldResponse holdA = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");
        PublicHoldResponse holdB = hold(host.getUsername(), et.getSlug(), slotAt(9), "b@test.com", "B");

        confirm(host.getUsername(), et.getSlug(), holdA.bookingId());

        assertThatThrownBy(() -> confirm(host.getUsername(), et.getSlug(), holdB.bookingId()))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_CAPACITY_FULL));

        assertThat(countRegistrationsByStatus(holdA.sessionId(), "CONFIRMED")).isEqualTo(1);
        assertThat(((Number) querySession(holdA.sessionId()).get("confirmed_count")).intValue()).isEqualTo(1);
    }

    // ── guest reschedule ──────────────────────────────────────────────────────

    /**
     * Group attendees can now move themselves between sessions. This previously threw
     * GROUP_ATTENDEE_RESCHEDULE_NOT_SUPPORTED and told the guest to cancel and re-book,
     * which released their seat before they had secured another.
     */
    @Test
    void groupReschedule_movesTheRegistrationToTheTargetSession() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 3);
        PublicHoldResponse hold = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");
        confirm(host.getUsername(), et.getSlug(), hold.bookingId());

        var response = publicBookingService.reschedule(
                host.getUsername(), et.getSlug(), hold.bookingId(),
                new io.bunnycal.booking.dto.PublicRescheduleRequest(slotAt(10)), null);

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.startTime()).isEqualTo(slotAt(10));
        // Seat released at 09:00 and taken at 10:00.
        assertThat(((Number) querySession(hold.sessionId()).get("confirmed_count")).intValue()).isZero();
        assertThat(countRegistrationsByStatus(hold.sessionId(), "CONFIRMED")).isZero();
    }

    /**
     * A PENDING hold reserves no seat, so there is nothing to transfer — moving one
     * would decrement a seat the source session never took.
     */
    @Test
    void groupReschedule_rejectsAnUnconfirmedHold() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 3);
        PublicHoldResponse hold = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");

        assertThatThrownBy(() -> publicBookingService.reschedule(
                host.getUsername(), et.getSlug(), hold.bookingId(),
                new io.bunnycal.booking.dto.PublicRescheduleRequest(slotAt(10)), null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    // ── slot visibility via end-to-end flow ───────────────────────────────────

    @Test
    void groupHold_slotBecomesHiddenAfterSessionFull() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 1);

        PublicHoldResponse hold = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");
        confirm(host.getUsername(), et.getSlug(), hold.bookingId());

        assertThat(querySession(hold.sessionId()).get("status")).isEqualTo("FULL");

        var slots = publicBookingService.availability(host.getUsername(), et.getSlug(), TEST_DATE);
        var slotStarts = slots.slots().stream()
                .map(io.bunnycal.availability.dto.SlotDto::start)
                .toList();
        assertThat(slotStarts).doesNotContain(slotAt(9));
        assertThat(slotStarts).contains(slotAt(10));
    }

    @Test
    void groupCancel_slotReappearsAfterCancellingConfirmedRegistration() {
        User host = createHostWithAvailability();
        EventType et = createGroupType(host.getId(), 1);

        PublicHoldResponse hold = hold(host.getUsername(), et.getSlug(), slotAt(9), "a@test.com", "A");
        PublicConfirmResponse conf = confirm(host.getUsername(), et.getSlug(), hold.bookingId());

        var slotsBefore = publicBookingService.availability(host.getUsername(), et.getSlug(), TEST_DATE);
        assertThat(slotsBefore.slots().stream().map(io.bunnycal.availability.dto.SlotDto::start).toList())
                .doesNotContain(slotAt(9));

        cancel(host.getUsername(), et.getSlug(), hold.bookingId(), conf.manageToken());

        var slotsAfter = publicBookingService.availability(host.getUsername(), et.getSlug(), TEST_DATE);
        assertThat(slotsAfter.slots().stream().map(io.bunnycal.availability.dto.SlotDto::start).toList())
                .contains(slotAt(9));
    }

    // ── ONE_ON_ONE unaffected ─────────────────────────────────────────────────

    @Test
    void oneOnOne_holdConfirmCancelUnaffectedByGroupBranching() {
        User host = createHostWithAvailability();
        // Create a ONE_ON_ONE type on same host.
        EventType oneOnOne = eventTypeRepository.save(
                io.bunnycal.availability.domain.EventType.builder()
                        .userId(host.getId())
                        .name("1-on-1")
                        .slug("one-on-one-" + UUID.randomUUID().toString().substring(0, 8))
                        .duration(Duration.ofHours(1))
                        .bufferBefore(Duration.ZERO)
                        .bufferAfter(Duration.ZERO)
                        .slotInterval(Duration.ofHours(1))
                        .minNotice(Duration.ZERO)
                        .maxAdvance(Duration.ofDays(365))
                        .holdDuration(Duration.ofMinutes(15))
                        .kind(io.bunnycal.availability.domain.EventKind.ONE_ON_ONE)
                        .capacity(1)
                        .build());

        // Hold via the public API — should go through ONE_ON_ONE path.
        PublicHoldResponse holdResp = hold(host.getUsername(), oneOnOne.getSlug(), slotAt(9), "g@test.com", "Guest");

        assertThat(holdResp.sessionId()).isNull();  // null for ONE_ON_ONE

        Integer bookingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings", Integer.class);
        assertThat(bookingCount).isEqualTo(1);

        Integer sessionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions", Integer.class);
        assertThat(sessionCount).isEqualTo(0);
    }
}
