package io.bunnycal.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.dto.PublicRescheduleRequest;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.service.BookingService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.enums.UserStatus;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PublicRoundRobinBookingIT extends AbstractBookingIT {

    // Next Monday ≥7 days from today — always within maxAdvance=30 days.
    private static final LocalDate TEST_DATE = nextMonday(7);

    private static LocalDate nextMonday(int minDaysFromNow) {
        LocalDate base = LocalDate.now().plusDays(minDaysFromNow);
        int daysUntilMonday = (DayOfWeek.MONDAY.getValue() - base.getDayOfWeek().getValue() + 7) % 7;
        return base.plusDays(daysUntilMonday);
    }

    private static Instant slotAt(int hour, int minute) {
        return TEST_DATE.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    @Autowired private PublicBookingService publicBookingService;
    @Autowired private EventTypeRepository eventTypeRepository;
    @Autowired private BookingService bookingService;

    @BeforeEach
    void cleanRoundRobinFixtures() {
        jdbc.execute("""
                TRUNCATE TABLE users, event_types, event_type_participants, availability_rules,
                    availability_overrides, bookings, booking_assignments, booking_action_tokens,
                    idempotency_keys, outbox_events, processed_events, calendar_connections,
                    calendar_events, calendar_event_mappings CASCADE
                """);
    }

    @Test
    void roundRobinBooking_assignsSingleContributorAndRestoresAvailabilityOnCancel() {
        User owner = createUser("owner-a@test.com", "owner-a");
        User alice = createUser("alice-a@test.com", "alice-a");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-a");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots()
                .stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst()
                .orElseThrow();

        var hold = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-a@test.com", "Guest A", slot.bookingToken()));
        var confirm = publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), hold.bookingId());

        Map<String, Object> bookingRow = jdbc.queryForMap(
                "SELECT host_id, status FROM bookings WHERE id = ?",
                hold.bookingId());
        assertThat(bookingRow.get("host_id")).isEqualTo(alice.getId());
        assertThat(bookingRow.get("status")).isEqualTo("CONFIRMED");

        Map<String, Object> assignmentRow = jdbc.queryForMap(
                "SELECT participant_user_id, assignment_reason FROM booking_assignments WHERE booking_id = ?",
                hold.bookingId());
        assertThat(assignmentRow.get("participant_user_id")).isEqualTo(alice.getId());
        assertThat(assignmentRow.get("assignment_reason")).isEqualTo("LEAST_RECENTLY_ASSIGNED");

        List<Instant> startsAfterConfirm = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots()
                .stream()
                .map(SlotDto::start)
                .toList();
        assertThat(startsAfterConfirm).doesNotContain(slot.start());

        publicBookingService.cancel(owner.getUsername(), eventType.getSlug(), hold.bookingId(), confirm.manageToken());

        Map<String, Object> cancelledRow = jdbc.queryForMap(
                "SELECT host_id, status FROM bookings WHERE id = ?",
                hold.bookingId());
        assertThat(cancelledRow.get("host_id")).isEqualTo(alice.getId());
        assertThat(cancelledRow.get("status")).isEqualTo("CANCELLED");

        List<Instant> startsAfterCancel = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots()
                .stream()
                .map(SlotDto::start)
                .toList();
        assertThat(startsAfterCancel).contains(slot.start());
    }

    @Test
    void roundRobinBooking_rotatesByLeastRecentlyAssignedAcrossParticipants() {
        User owner = createUser("owner-c@test.com", "owner-c");
        User alice = createUser("alice-c@test.com", "alice-c");
        User bob = createUser("bob-c@test.com", "bob-c");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-c");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());
        insertCalendarConnection(bob.getId());

        List<SlotDto> initialSlots = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE).slots();
        SlotDto first = initialSlots.stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst()
                .orElseThrow();
        SlotDto second = initialSlots.stream()
                .filter(s -> s.start().equals(slotAt(9, 30)))
                .findFirst()
                .orElseThrow();

        UUID bookingOne = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(first.start(), "guest1@test.com", "Guest 1", first.bookingToken())).bookingId();
        publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), bookingOne);

        UUID bookingTwo = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(second.start(), "guest2@test.com", "Guest 2", second.bookingToken())).bookingId();
        publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), bookingTwo);

        UUID firstHost = jdbc.queryForObject("SELECT host_id FROM bookings WHERE id = ?", UUID.class, bookingOne);
        UUID secondHost = jdbc.queryForObject("SELECT host_id FROM bookings WHERE id = ?", UUID.class, bookingTwo);
        assertThat(firstHost).isEqualTo(alice.getId());
        assertThat(secondHost).isEqualTo(bob.getId());
    }

    @Test
    void roundRobinReschedule_preservesAssignmentAndMovesBusyWindow() {
        User owner = createUser("owner-r@test.com", "owner-r");
        User alice = createUser("alice-r@test.com", "alice-r");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-r");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(12, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotDto originalSlot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots()
                .stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst()
                .orElseThrow();

        var hold = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(originalSlot.start(), "guest-r@test.com", "Guest R", originalSlot.bookingToken()));
        var confirm = publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), hold.bookingId());
        Instant rescheduledStart = slotAt(10, 0);

        publicBookingService.reschedule(
                owner.getUsername(),
                eventType.getSlug(),
                hold.bookingId(),
                new PublicRescheduleRequest(rescheduledStart),
                confirm.manageToken());

        Map<String, Object> bookingRow = jdbc.queryForMap(
                "SELECT host_id, start_time, end_time FROM bookings WHERE id = ?",
                hold.bookingId());
        assertThat(bookingRow.get("host_id")).isEqualTo(alice.getId());
        assertThat(((java.sql.Timestamp) bookingRow.get("start_time")).toInstant()).isEqualTo(rescheduledStart);
        assertThat(((java.sql.Timestamp) bookingRow.get("end_time")).toInstant()).isEqualTo(rescheduledStart.plus(Duration.ofMinutes(30)));

        UUID assignmentParticipant = jdbc.queryForObject(
                "SELECT participant_user_id FROM booking_assignments WHERE booking_id = ?",
                UUID.class,
                hold.bookingId());
        assertThat(assignmentParticipant).isEqualTo(alice.getId());

        List<Instant> currentStarts = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots()
                .stream()
                .map(SlotDto::start)
                .toList();
        assertThat(currentStarts).contains(originalSlot.start());
        assertThat(currentStarts).doesNotContain(rescheduledStart);
    }

    @Test
    void roundRobinBooking_rejectsTamperedSlotToken() {
        User owner = createUser("owner-j@test.com", "owner-j");
        User alice = createUser("alice-j@test.com", "alice-j");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-j");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0));
        insertCalendarConnection(owner.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots()
                .get(0);
        String[] tokenParts = slot.bookingToken().split("\\.", 2);
        String tamperedPayload = (tokenParts[0].startsWith("A") ? "B" : "A") + tokenParts[0].substring(1);
        String tampered = tamperedPayload + "." + tokenParts[1];

        assertThatThrownBy(() -> publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-j@test.com", "Guest J", tampered)))
                .isInstanceOf(CustomException.class)
                .extracting(ex -> ((CustomException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ── Audit #1: Confirm-time eligibility revalidation ──────────────────────

    @Test
    void roundRobinConfirm_rejectsWhenParticipantGoesInactiveBetweenHoldAndConfirm() {
        User owner = createUser("owner-ci@test.com", "owner-ci");
        User alice = createUser("alice-ci@test.com", "alice-ci");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-ci");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        var hold = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-ci@test.com", "Guest CI", slot.bookingToken()));

        // Participant goes INACTIVE after hold but before confirm
        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", alice.getId());

        assertThatThrownBy(() -> publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), hold.bookingId()))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode()).isEqualTo(ErrorCode.SLOT_UNAVAILABLE));
    }

    @Test
    void roundRobinConfirm_rejectsWhenParticipantDeletesAvailabilityRulesBetweenHoldAndConfirm() {
        User owner = createUser("owner-cr@test.com", "owner-cr");
        User alice = createUser("alice-cr@test.com", "alice-cr");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-cr");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        var hold = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-cr@test.com", "Guest CR", slot.bookingToken()));

        // Remove all availability rules after hold
        jdbc.update("DELETE FROM availability_rules WHERE user_id = ?", alice.getId());

        assertThatThrownBy(() -> publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), hold.bookingId()))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode()).isEqualTo(ErrorCode.SLOT_UNAVAILABLE));
    }

    // ── Audit #5: Reschedule outside availability window ─────────────────────

    @Test
    void roundRobinReschedule_rejectsTimeOutsideParticipantAvailability() {
        User owner = createUser("owner-rs@test.com", "owner-rs");
        User alice = createUser("alice-rs@test.com", "alice-rs");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-rs");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0)); // 9-11 only
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        var hold = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-rs@test.com", "Guest RS", slot.bookingToken()));
        var confirm = publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), hold.bookingId());

        // Try to reschedule to 14:00 — outside alice's availability window (9-11)
        Instant outsideWindow = slotAt(14, 0);
        assertThatThrownBy(() -> publicBookingService.reschedule(
                owner.getUsername(), eventType.getSlug(), hold.bookingId(),
                new PublicRescheduleRequest(outsideWindow), confirm.manageToken()))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode()).isEqualTo(ErrorCode.SLOT_UNAVAILABLE));
    }

    @Test
    void roundRobinReschedule_rejectsWhenParticipantGoesInactiveBeforeReschedule() {
        User owner = createUser("owner-ri@test.com", "owner-ri");
        User alice = createUser("alice-ri@test.com", "alice-ri");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-ri");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(12, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        var hold = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-ri@test.com", "Guest RI", slot.bookingToken()));
        var confirm = publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), hold.bookingId());

        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", alice.getId());

        Instant newTime = slotAt(10, 0);
        assertThatThrownBy(() -> publicBookingService.reschedule(
                owner.getUsername(), eventType.getSlug(), hold.bookingId(),
                new PublicRescheduleRequest(newTime), confirm.manageToken()))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode()).isEqualTo(ErrorCode.SLOT_UNAVAILABLE));
    }

    // ── Audit #4: Reschedule preserves original assignee ─────────────────────

    @Test
    void roundRobinReschedule_preservesOriginalAssigneeNotReassign() {
        User owner = createUser("owner-rp@test.com", "owner-rp");
        User alice = createUser("alice-rp@test.com", "alice-rp");
        User bob = createUser("bob-rp@test.com", "bob-rp");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-rp");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(12, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(12, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());
        insertCalendarConnection(bob.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        var hold = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-rp@test.com", "Guest RP", slot.bookingToken()));
        var confirm = publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), hold.bookingId());

        UUID originalHostId = jdbc.queryForObject("SELECT host_id FROM bookings WHERE id = ?", UUID.class, hold.bookingId());

        // Reschedule to a different time within availability
        Instant newTime = slotAt(10, 0);
        publicBookingService.reschedule(owner.getUsername(), eventType.getSlug(), hold.bookingId(),
                new PublicRescheduleRequest(newTime), confirm.manageToken());

        // Assignment must remain the same participant — no re-rotation
        UUID hostAfterReschedule = jdbc.queryForObject("SELECT host_id FROM bookings WHERE id = ?", UUID.class, hold.bookingId());
        UUID assignedParticipant = jdbc.queryForObject("SELECT participant_user_id FROM booking_assignments WHERE booking_id = ?", UUID.class, hold.bookingId());
        assertThat(hostAfterReschedule).isEqualTo(originalHostId);
        assertThat(assignedParticipant).isEqualTo(originalHostId);
    }

    // ── Audit #7: Participant removal — existing bookings remain intact ───────

    @Test
    void roundRobinParticipantRemoval_existingBookingRemainsAccessibleAndCancellable() {
        User owner = createUser("owner-pr@test.com", "owner-pr");
        User alice = createUser("alice-pr@test.com", "alice-pr");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-pr");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        var hold = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-pr@test.com", "Guest PR", slot.bookingToken()));
        var confirm = publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), hold.bookingId());

        // Remove alice from the event type participants
        jdbc.update("DELETE FROM event_type_participants WHERE event_type_id = ?", eventType.getId());

        // The existing booking must still be cancellable via the assigned participant's
        // composite PK (hostId = alice) — using the guest token
        publicBookingService.cancel(owner.getUsername(), eventType.getSlug(), hold.bookingId(), confirm.manageToken());

        String status = jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, hold.bookingId());
        assertThat(status).isEqualTo("CANCELLED");

        // Assignment record must still be intact
        UUID assignedParticipant = jdbc.queryForObject("SELECT participant_user_id FROM booking_assignments WHERE booking_id = ?", UUID.class, hold.bookingId());
        assertThat(assignedParticipant).isEqualTo(alice.getId());
    }

    // ── Audit #4/#7: Event owner can cancel RR booking even if not assigned ──

    @Test
    void roundRobinCancelAsOwner_eventOwnerCanCancelRRBookingTheyOwnEventTypeFor() {
        User owner = createUser("owner-oc@test.com", "owner-oc");
        User alice = createUser("alice-oc@test.com", "alice-oc");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-oc");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        UUID bookingId = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-oc@test.com", "Guest OC", slot.bookingToken())).bookingId();
        publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), bookingId);

        // Booking hostId is alice, but owner should be able to cancel it
        assertThat(jdbc.queryForObject("SELECT host_id FROM bookings WHERE id = ?", UUID.class, bookingId))
                .isEqualTo(alice.getId());

        Booking cancelled = bookingService.cancelBookingAsHost(bookingId, owner.getId(), null);
        assertThat(cancelled).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .isEqualTo("CANCELLED");
    }

    @Test
    void roundRobinCancelAsOwner_randomUserCannotCancelRRBooking() {
        User owner = createUser("owner-rc@test.com", "owner-rc");
        User alice = createUser("alice-rc@test.com", "alice-rc");
        User stranger = createUser("stranger-rc@test.com", "stranger-rc");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-rc");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        UUID bookingId = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-rc@test.com", "Guest RC", slot.bookingToken())).bookingId();
        publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), bookingId);

        assertThatThrownBy(() -> bookingService.cancelBookingAsHost(bookingId, stranger.getId(), null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    // ── Audit #8: Backend eligibility enforcement — API-level bypass attempt ─

    @Test
    void roundRobinSlotGeneration_excludesIneligibleParticipants_noAvailabilityRules() {
        User owner = createUser("owner-el@test.com", "owner-el");
        User alice = createUser("alice-el@test.com", "alice-el");
        User bob = createUser("bob-el@test.com", "bob-el"); // no rules — must be excluded

        EventType eventType = createRoundRobinType(owner.getId(), "rr-el");
        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        // bob has NO rules
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        var slots = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE).slots();
        assertThat(slots).isNotEmpty(); // alice contributes slots

        SlotDto slot = slots.stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        var hold = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "guest-el@test.com", "Guest EL", slot.bookingToken()));
        publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), hold.bookingId());

        // Booking must be assigned to alice, not bob
        UUID hostId = jdbc.queryForObject("SELECT host_id FROM bookings WHERE id = ?", UUID.class, hold.bookingId());
        assertThat(hostId).isEqualTo(alice.getId());
    }

    @Test
    void roundRobinSlotGeneration_excludesInactiveParticipants() {
        User owner = createUser("owner-ia@test.com", "owner-ia");
        User alice = createUser("alice-ia@test.com", "alice-ia");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-ia");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnection(owner.getId());

        // Make alice inactive before slot generation
        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", alice.getId());

        var slots = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE).slots();
        assertThat(slots).isEmpty();
    }

    // ── Audit #9: Tie-breaking is deterministic ───────────────────────────────

    @Test
    void roundRobinAssignment_tieBreakedDeterministicallyByTokenOrderThenUUID() {
        User owner = createUser("owner-tb@test.com", "owner-tb");
        User alice = createUser("alice-tb@test.com", "alice-tb");
        User bob = createUser("bob-tb@test.com", "bob-tb");
        EventType eventType = createRoundRobinType(owner.getId(), "rr-tb");

        // Both participants with identical empty assignment history → token order determines first pick
        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());
        insertCalendarConnection(bob.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        // Book the same slot twice (refreshing availability between attempts) and verify
        // the SAME participant always wins first pick when no assignment history exists.
        UUID bookingId1 = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot.start(), "g1@test.com", "G1", slot.bookingToken())).bookingId();
        publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), bookingId1);
        UUID first = jdbc.queryForObject("SELECT host_id FROM bookings WHERE id = ?", UUID.class, bookingId1);

        // Second booking at a non-overlapping slot — now first participant has an assignment,
        // so second booking goes to the other one.
        SlotDto slot2 = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 30)))
                .findFirst().orElseThrow();
        UUID bookingId2 = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(slot2.start(), "g2@test.com", "G2", slot2.bookingToken())).bookingId();
        publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), bookingId2);
        UUID second = jdbc.queryForObject("SELECT host_id FROM bookings WHERE id = ?", UUID.class, bookingId2);

        // The two assignments must be different (rotation happened)
        assertThat(first).isNotEqualTo(second);
        // Both must be one of our participants
        assertThat(List.of(alice.getId(), bob.getId())).contains(first);
        assertThat(List.of(alice.getId(), bob.getId())).contains(second);
    }

    // ── Audit #10: Cross-event blocking — RR booking blocks same participant ─

    @Test
    void roundRobinBooking_blocksParticipantAcrossOtherEventTypes() {
        User owner = createUser("owner-xe@test.com", "owner-xe");
        User alice = createUser("alice-xe@test.com", "alice-xe");

        EventType rrTypeA = createRoundRobinType(owner.getId(), "rr-xe-a");
        EventType rrTypeB = createRoundRobinType(owner.getId(), "rr-xe-b");

        setParticipants(rrTypeA.getId(), List.of(alice.getId()));
        setParticipants(rrTypeB.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0));
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        // Book alice via event type A at 09:00
        SlotDto slotA = publicBookingService.availability(owner.getUsername(), rrTypeA.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();
        var holdA = publicBookingService.hold(owner.getUsername(), rrTypeA.getSlug(),
                new PublicBookRequest(slotA.start(), "guest-xe@test.com", "Guest XE", slotA.bookingToken()));
        publicBookingService.confirm(owner.getUsername(), rrTypeA.getSlug(), holdA.bookingId());

        // Event type B at the same time must now return no slots (alice is busy)
        var slotsB = publicBookingService.availability(owner.getUsername(), rrTypeB.getSlug(), TEST_DATE).slots();
        assertThat(slotsB.stream().anyMatch(s -> s.start().equals(slotAt(9, 0)))).isFalse();
    }

    private User createUser(String email, String username) {
        return userRepository.save(User.builder()
                .email(email)
                .username(username)
                .name(username)
                .timezone("UTC")
                .status(UserStatus.ACTIVE)
                .build());
    }

    private EventType createRoundRobinType(UUID ownerId, String slugPrefix) {
        return eventTypeRepository.save(EventType.builder()
                .userId(ownerId)
                .name("RR " + slugPrefix)
                .slug(slugPrefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.ROUND_ROBIN)
                .capacity(1)
                .conferencingProvider(ConferencingProviderType.NONE)
                .build());
    }

    private void setParticipants(UUID eventTypeId, List<UUID> participantIds) {
        jdbc.update("DELETE FROM event_type_participants WHERE event_type_id = ?", eventTypeId);
        for (int i = 0; i < participantIds.size(); i++) {
            jdbc.update(
                    "INSERT INTO event_type_participants (id, event_type_id, user_id, display_order) VALUES (?,?,?,?)",
                    UUID.randomUUID(), eventTypeId, participantIds.get(i), i);
        }
    }

    private void insertRule(UUID userId, String dayOfWeek, LocalTime startTime, LocalTime endTime) {
        jdbc.update(
                "INSERT INTO availability_rules (id, user_id, day_of_week, start_time, end_time) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), userId, dayOfWeek, startTime, endTime);
    }

    private void insertCalendarConnection(UUID userId) {
        jdbc.update(
                "INSERT INTO calendar_connections "
                        + "(id, user_id, provider, provider_user_id, refresh_token_ciphertext, last_token_expires_at, scopes, status, version) "
                        + "VALUES (?,?,?,?,?,NOW() + INTERVAL '1 hour','{}'::text[],?,0)",
                UUID.randomUUID(), userId, "GOOGLE", "ext-" + userId, "dummy-token", "ACTIVE");
    }
}
