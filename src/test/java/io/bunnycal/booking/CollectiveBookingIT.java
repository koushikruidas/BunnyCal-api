package io.bunnycal.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.common.enums.UserStatus;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.AssignmentReason;
import io.bunnycal.booking.domain.BookingAssignment;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.dto.PublicConfirmResponse;
import io.bunnycal.booking.dto.PublicHoldResponse;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.booking.service.CollectiveSlotTokenService;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for Phase 4: Collective booking hold / confirm / cancel.
 *
 * <p>Test slot: 2026-06-15T09:00Z – 2026-06-15T09:30Z (Monday UTC).
 */
@Testcontainers(disabledWithoutDocker = true)
class CollectiveBookingIT extends AbstractBookingIT {

    private static final Instant SLOT_START  = Instant.parse("2026-06-15T09:00:00Z");
    private static final Instant SLOT_END    = Instant.parse("2026-06-15T09:30:00Z");
    private static final Duration HOLD_DUR   = Duration.ofMinutes(15);

    @Autowired private PublicBookingService publicBookingService;
    @Autowired private EventTypeRepository eventTypeRepository;
    @Autowired private CollectiveSlotTokenService collectiveSlotTokenService;
    @Autowired private BookingAssignmentRepository assignmentRepository;

    @BeforeEach
    void cleanFixtures() {
        jdbc.execute("""
                TRUNCATE TABLE users, event_types, event_type_participants, availability_rules,
                    bookings, booking_assignments, booking_action_tokens,
                    collective_participant_holds,
                    idempotency_keys, outbox_events, processed_events,
                    calendar_connections, calendar_connection_calendars,
                    calendar_events, calendar_event_mappings CASCADE
                """);
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    void holdAndConfirm_happyPath() {
        Fixture f = buildFixture("hc");
        String token = collectiveSlotTokenService.issue(
                f.owner.getId(), f.eventType.getId(), SLOT_START, SLOT_END,
                List.of(f.alice.getId(), f.bob.getId()));

        PublicHoldResponse hold = publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "guest@example.com", "Guest", token));

        assertThat(hold.bookingId()).isNotNull();
        assertThat(hold.startTime()).isEqualTo(SLOT_START);
        assertThat(hold.endTime()).isEqualTo(SLOT_END);

        // Two BookingAssignment rows with COLLECTIVE_ALL reason
        List<BookingAssignment> assignments = assignmentRepository.findAllByBookingId(hold.bookingId());
        assertThat(assignments).hasSize(2);
        assertThat(assignments).allMatch(a -> a.getAssignmentReason() == AssignmentReason.COLLECTIVE_ALL);
        assertThat(assignments.stream().map(BookingAssignment::getParticipantUserId).toList())
                .containsExactlyInAnyOrder(f.alice.getId(), f.bob.getId());

        // Two holds in collective_participant_holds
        int holdCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM collective_participant_holds WHERE booking_id = ? AND released_at IS NULL",
                Integer.class, hold.bookingId());
        assertThat(holdCount).isEqualTo(2);

        // Confirm
        PublicConfirmResponse confirm = publicBookingService.confirm(
                f.owner.getUsername(), f.eventType.getSlug(), hold.bookingId());
        assertThat(confirm.bookingId()).isEqualTo(hold.bookingId());

        // Holds released after confirm
        int activeHolds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM collective_participant_holds WHERE booking_id = ? AND released_at IS NULL",
                Integer.class, hold.bookingId());
        assertThat(activeHolds).isZero();

        // Booking is CONFIRMED
        String status = jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, hold.bookingId());
        assertThat(status).isEqualTo("CONFIRMED");
    }

    @Test
    void holdConfirmCancel_releasesParticipantHolds() {
        Fixture f = buildFixture("cancel");
        String token = collectiveSlotTokenService.issue(
                f.owner.getId(), f.eventType.getId(), SLOT_START, SLOT_END,
                List.of(f.alice.getId(), f.bob.getId()));

        PublicHoldResponse hold = publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "guest@example.com", "Guest", token));

        // Confirm — releases holds and issues a manage token
        PublicConfirmResponse confirm = publicBookingService.confirm(
                f.owner.getUsername(), f.eventType.getSlug(), hold.bookingId());
        assertThat(activeHoldCount(hold.bookingId())).isZero();

        // Cancel with the manage token — booking moves to CANCELLED
        publicBookingService.cancel(f.owner.getUsername(), f.eventType.getSlug(),
                hold.bookingId(), confirm.manageToken());

        String status = jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, hold.bookingId());
        assertThat(status).isEqualTo("CANCELLED");
    }

    // ── Token validation ───────────────────────────────────────────────────────

    @Test
    void hold_missingToken_rejected() {
        Fixture f = buildFixture("no-token");
        assertThatThrownBy(() -> publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "g@x.com", "G", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void hold_staleToken_afterRosterChange_rejected() {
        Fixture f = buildFixture("stale");
        // Token issued for [alice, bob]
        String token = collectiveSlotTokenService.issue(
                f.owner.getId(), f.eventType.getId(), SLOT_START, SLOT_END,
                List.of(f.alice.getId(), f.bob.getId()));

        // Charlie is added to the event type
        User charlie = createUser("charlie-stale@test.com", "charlie-stale");
        insertRule(charlie.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(charlie.getId());
        setParticipants(f.eventType.getId(), List.of(f.alice.getId(), f.bob.getId(), charlie.getId()));

        // Token now has wrong roster hash → VALIDATION_ERROR
        assertThatThrownBy(() -> publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "g@x.com", "G", token)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void hold_tokenSlotMismatch_rejected() {
        Fixture f = buildFixture("mismatch");
        // Token for a different start time
        Instant otherStart = SLOT_START.plus(Duration.ofHours(1));
        Instant otherEnd   = SLOT_END.plus(Duration.ofHours(1));
        String token = collectiveSlotTokenService.issue(
                f.owner.getId(), f.eventType.getId(), otherStart, otherEnd,
                List.of(f.alice.getId(), f.bob.getId()));

        assertThatThrownBy(() -> publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "g@x.com", "G", token)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ── Participant readiness at hold time ──────────────────────────────────────

    @Test
    void hold_participantLostCalendar_rejected() {
        Fixture f = buildFixture("no-cal");
        // Alice's calendar connection is removed after token issuance
        String token = collectiveSlotTokenService.issue(
                f.owner.getId(), f.eventType.getId(), SLOT_START, SLOT_END,
                List.of(f.alice.getId(), f.bob.getId()));
        jdbc.update("DELETE FROM calendar_connections WHERE user_id = ?", f.alice.getId());

        assertThatThrownBy(() -> publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "g@x.com", "G", token)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SLOT_UNAVAILABLE));
    }

    // ── Conflict protection ─────────────────────────────────────────────────────

    @Test
    void secondHold_sameSlot_sameParticipants_onlyOneSucceeds() {
        Fixture f = buildFixture("concur");
        String token1 = collectiveSlotTokenService.issue(
                f.owner.getId(), f.eventType.getId(), SLOT_START, SLOT_END,
                List.of(f.alice.getId(), f.bob.getId()));
        String token2 = collectiveSlotTokenService.issue(
                f.owner.getId(), f.eventType.getId(), SLOT_START, SLOT_END,
                List.of(f.alice.getId(), f.bob.getId()));

        PublicHoldResponse first = publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "g1@x.com", "G1", token1));
        assertThat(first.bookingId()).isNotNull();

        // Second hold for the same slot must be rejected via EXCLUDE constraint
        assertThatThrownBy(() -> publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "g2@x.com", "G2", token2)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SLOT_UNAVAILABLE));
    }

    // ── Reschedule disabled ─────────────────────────────────────────────────────

    @Test
    void reschedule_collective_notSupported() {
        Fixture f = buildFixture("resched");
        String token = collectiveSlotTokenService.issue(
                f.owner.getId(), f.eventType.getId(), SLOT_START, SLOT_END,
                List.of(f.alice.getId(), f.bob.getId()));
        PublicHoldResponse hold = publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "g@x.com", "G", token));
        PublicConfirmResponse confirm = publicBookingService.confirm(
                f.owner.getUsername(), f.eventType.getSlug(), hold.bookingId());

        assertThatThrownBy(() -> publicBookingService.reschedule(
                f.owner.getUsername(), f.eventType.getSlug(), hold.bookingId(),
                new io.bunnycal.booking.dto.PublicRescheduleRequest(SLOT_START.plus(Duration.ofDays(1))),
                confirm.manageToken()))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private record Fixture(User owner, User alice, User bob, EventType eventType) {}

    private Fixture buildFixture(String suffix) {
        User owner = createUser("owner-" + suffix + "@test.com", "owner-" + suffix);
        User alice = createUser("alice-" + suffix + "@test.com", "alice-" + suffix);
        User bob   = createUser("bob-"   + suffix + "@test.com", "bob-"   + suffix);

        EventType et = eventTypeRepository.save(EventType.builder()
                .userId(owner.getId())
                .name("Collective " + suffix)
                .slug("coll-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6))
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(30))
                .holdDuration(HOLD_DUR)
                .kind(EventKind.COLLECTIVE)
                .capacity(1)
                .conferencingProvider(ConferencingProviderType.NONE)
                .build());

        setParticipants(et.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(17, 0));
        insertRule(bob.getId(),   "MONDAY", LocalTime.of(9, 0), LocalTime.of(17, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());
        return new Fixture(owner, alice, bob, et);
    }

    private User createUser(String email, String username) {
        return userRepository.save(User.builder()
                .email(email).username(username).name(username)
                .timezone("UTC").status(UserStatus.ACTIVE).build());
    }

    private void setParticipants(UUID eventTypeId, List<UUID> ids) {
        jdbc.update("DELETE FROM event_type_participants WHERE event_type_id = ?", eventTypeId);
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("INSERT INTO event_type_participants (id, event_type_id, user_id, display_order) VALUES (?,?,?,?)",
                    UUID.randomUUID(), eventTypeId, ids.get(i), i);
        }
    }

    private void insertRule(UUID userId, String day, LocalTime from, LocalTime to) {
        jdbc.update("""
                INSERT INTO availability_rules
                    (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, ?::time, ?::time, NOW(), NOW())
                """, userId, day, from.toString(), to.toString());
    }

    private void insertCalendarConnectionWithWriteback(UUID userId) {
        UUID connId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO calendar_connections
                    (id, user_id, provider, provider_user_id, refresh_token_ciphertext,
                     last_token_expires_at, scopes, status, version, last_synced_at)
                VALUES (?,?,?,?,?,NOW() + INTERVAL '1 hour','{}'::text[],?,0,NOW())
                """, connId, userId, "GOOGLE", "ext-" + userId, "dummy-token", "ACTIVE");
        jdbc.update("""
                INSERT INTO calendar_connection_calendars
                    (id, connection_id, external_calendar_id, name, is_primary, is_selected,
                     sync_enabled, can_read, can_write, hidden)
                VALUES (?,?,?,?,true,true,true,true,true,false)
                """, UUID.randomUUID(), connId, "primary@" + userId, "Primary");
    }

    private int activeHoldCount(UUID bookingId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM collective_participant_holds WHERE booking_id = ? AND released_at IS NULL",
                Integer.class, bookingId);
    }

}
