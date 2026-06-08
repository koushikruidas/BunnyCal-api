package io.bunnycal.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.dto.PublicRescheduleRequest;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.enums.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PublicRoundRobinBookingIT extends AbstractBookingIT {

    private static final LocalDate TEST_DATE = LocalDate.of(2026, 6, 15); // Monday

    @Autowired private PublicBookingService publicBookingService;
    @Autowired private EventTypeRepository eventTypeRepository;

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
                .filter(s -> s.start().equals(Instant.parse("2026-06-15T09:00:00Z")))
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
                .filter(s -> s.start().equals(Instant.parse("2026-06-15T09:00:00Z")))
                .findFirst()
                .orElseThrow();
        SlotDto second = initialSlots.stream()
                .filter(s -> s.start().equals(Instant.parse("2026-06-15T09:30:00Z")))
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
                .filter(s -> s.start().equals(Instant.parse("2026-06-15T09:00:00Z")))
                .findFirst()
                .orElseThrow();

        var hold = publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                new PublicBookRequest(originalSlot.start(), "guest-r@test.com", "Guest R", originalSlot.bookingToken()));
        var confirm = publicBookingService.confirm(owner.getUsername(), eventType.getSlug(), hold.bookingId());
        Instant rescheduledStart = Instant.parse("2026-06-15T10:00:00Z");

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
        String tampered = slot.bookingToken().substring(0, slot.bookingToken().length() - 1)
                + (slot.bookingToken().endsWith("A") ? "B" : "A");

        try {
            publicBookingService.hold(owner.getUsername(), eventType.getSlug(),
                    new PublicBookRequest(slot.start(), "guest-j@test.com", "Guest J", tampered));
        } catch (CustomException ex) {
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            return;
        }
        throw new AssertionError("Expected invalid slot token to be rejected");
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
