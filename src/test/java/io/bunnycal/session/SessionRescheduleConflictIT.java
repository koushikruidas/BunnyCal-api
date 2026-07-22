package io.bunnycal.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.session.service.RescheduleConflictService;
import io.bunnycal.session.service.RescheduleConflicts;
import io.bunnycal.session.service.SessionService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Host reschedule of a group session against everything already occupying the host.
 *
 * <p>The distinction under test is occupancy vs. availability: working hours and the recurrence
 * rule must never block (overriding them is the feature), while real commitments must.
 */
class SessionRescheduleConflictIT extends AbstractSessionIT {

    @Autowired SessionService sessionService;
    @Autowired RescheduleConflictService conflictService;

    // ── Overlap semantics ────────────────────────────────────────────────────

    @Test
    @DisplayName("overlap is detected even when start times differ")
    void partialOverlapConflicts() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();

        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));
        // 30 minutes into the target window — an exact-start check would miss this entirely.
        Instant target = base.plus(Duration.ofHours(3));
        insertSession(host.getId(), et.getId(),
                target.plus(Duration.ofMinutes(30)), target.plus(Duration.ofMinutes(90)));

        assertThatThrownBy(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SLOT_UNAVAILABLE));
    }

    @Test
    @DisplayName("abutting meetings are not conflicts")
    void abuttingIsAllowed() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();

        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));
        Instant target = base.plus(Duration.ofHours(5));
        // Ends exactly when the moved session would start.
        insertSession(host.getId(), et.getId(), target.minus(Duration.ofHours(1)), target);

        assertThatCode(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a session does not conflict with itself when nudged within its own span")
    void selfOverlapIsNotAConflict() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        // Shifted 15 minutes later, so old and new spans overlap each other.
        assertThatCode(() -> sessionService.rescheduleSession(
                moving, host.getId(), base.plus(Duration.ofMinutes(15))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sessions of a different event type still block — the host cannot run two at once")
    void conflictSpansEventTypes() {
        User host = createHost();
        EventType a = createGroupEventType(host.getId(), 10);
        EventType b = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();

        UUID moving = insertSession(host.getId(), a.getId(), base, base.plus(Duration.ofHours(1)));
        Instant target = base.plus(Duration.ofHours(3));
        insertSession(host.getId(), b.getId(), target, target.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .isInstanceOf(CustomException.class);
    }

    // ── Bookings: 1:1, round-robin, collective ───────────────────────────────

    @Test
    @DisplayName("a 1:1 booking the host owns blocks")
    void ownedBookingBlocks() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        Instant target = base.plus(Duration.ofHours(3));
        insertBooking(host.getId(), et.getId(), target, target.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SLOT_UNAVAILABLE));
    }

    @Test
    @DisplayName("a collective booking blocks the host even when someone else is host_id")
    void collectiveParticipationBlocks() {
        User host = createHost();
        User teamOwner = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        EventType collectiveEt = createGroupEventType(teamOwner.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        Instant target = base.plus(Duration.ofHours(3));
        // host_id is the team owner; our host is only reachable via booking_assignments.
        // A host_id-only predicate would report this slot as free.
        UUID bookingId = insertBooking(teamOwner.getId(), collectiveEt.getId(),
                target, target.plus(Duration.ofHours(1)));
        insertAssignment(bookingId, host.getId(), "COLLECTIVE_ALL");

        assertThatThrownBy(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SLOT_UNAVAILABLE));
    }

    @Test
    @DisplayName("a round-robin booking assigned to the host blocks")
    void roundRobinAssignmentBlocks() {
        User host = createHost();
        User teamOwner = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        EventType rrEt = createGroupEventType(teamOwner.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        Instant target = base.plus(Duration.ofHours(3));
        UUID bookingId = insertBooking(teamOwner.getId(), rrEt.getId(),
                target, target.plus(Duration.ofHours(1)));
        insertAssignment(bookingId, host.getId(), "ROUND_ROBIN");

        assertThatThrownBy(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("a booking assigned to someone else does not block")
    void otherParticipantsBookingDoesNotBlock() {
        User host = createHost();
        User colleague = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        EventType otherEt = createGroupEventType(colleague.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        Instant target = base.plus(Duration.ofHours(3));
        UUID bookingId = insertBooking(colleague.getId(), otherEt.getId(),
                target, target.plus(Duration.ofHours(1)));
        insertAssignment(bookingId, colleague.getId(), "ROUND_ROBIN");

        assertThatCode(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a cancelled booking does not block")
    void cancelledBookingDoesNotBlock() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        Instant target = base.plus(Duration.ofHours(3));
        jdbc.update("""
                INSERT INTO bookings
                    (id, host_id, event_type_id, start_time, end_time, guest_email, guest_name,
                     status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'g@test.com', 'G', 'CANCELLED', 1, NOW(), NOW())
                """, UUID.randomUUID(), host.getId(), et.getId(),
                Timestamp.from(target), Timestamp.from(target.plus(Duration.ofHours(1))));

        assertThatCode(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a cancelled session does not occupy the host, so overlapping it is fine")
    void cancelledSessionDoesNotBlockOverlap() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        Instant target = base.plus(Duration.ofHours(3));
        // Overlapping but not sharing a start time, so the unique constraint is not in play.
        UUID cancelled = insertSession(host.getId(), et.getId(),
                target.plus(Duration.ofMinutes(30)), target.plus(Duration.ofMinutes(90)));
        jdbc.update("UPDATE event_sessions SET status = 'CANCELLED' WHERE id = ?", cancelled);

        assertThatCode(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the host can move a session onto a slot they previously cancelled")
    void hostCanRescheduleOntoASlotTheyCancelled() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        Instant target = base.plus(Duration.ofHours(3));
        UUID cancelled = insertSession(host.getId(), et.getId(), target, target.plus(Duration.ofHours(1)));
        jdbc.update("UPDATE event_sessions SET status = 'CANCELLED' WHERE id = ?", cancelled);

        // The host cancelled that class and is now choosing to run one at that time again. Whether
        // that hour is open is their call, and a dead row must not overrule it.
        assertThatCode(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .doesNotThrowAnyException();

        assertThat(jdbc.queryForObject(
                "SELECT start_time FROM event_sessions WHERE id = ?", Timestamp.class, moving))
                .isEqualTo(Timestamp.from(target));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM event_sessions WHERE id = ?", String.class, cancelled))
                .as("history is kept — the cancelled session is not resurrected")
                .isEqualTo("CANCELLED");

        var join = sessionService.joinSession(
                host.getId(), et.getId(), target, target.plus(Duration.ofHours(1)), 10,
                "new-guest@test.com", "New Guest", Duration.ofMinutes(15));
        assertThat(join.sessionId())
                .as("exact-time lookup must prefer the live moved session over terminal history")
                .isEqualTo(moving);
    }

    /**
     * The counterpart to {@link #hostCanRescheduleOntoASlotTheyCancelled}. Releasing the slot is a
     * host privilege, not a general reopening: a guest joining that hour must still be refused, or
     * cancelling a class would silently let strangers book it again.
     */
    @Test
    @DisplayName("a guest still cannot book into a cancelled session")
    void guestCannotJoinACancelledSlot() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        UUID cancelled = insertSession(host.getId(), et.getId(), start, end);
        jdbc.update("UPDATE event_sessions SET status = 'CANCELLED' WHERE id = ?", cancelled);

        assertThatThrownBy(() -> sessionService.joinSession(
                host.getId(), et.getId(), start, end, 10,
                "guest@test.com", "Guest", Duration.ofMinutes(15)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_CANCELLED));
    }

    @Test
    @DisplayName("a live session at the same exact start still blocks")
    void exactStartCollisionWithLiveSessionIsReported() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        Instant target = base.plus(Duration.ofHours(3));
        insertSession(host.getId(), et.getId(), target, target.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SLOT_UNAVAILABLE));
    }

    /**
     * Preview and write must agree in both directions: the dialog must not offer a time the write
     * refuses, and must not block one the write would accept. A cancelled session is the case that
     * distinguishes them, since it looks like an occupant but is not one.
     */
    @Test
    @DisplayName("preview does not flag a cancelled session the write would allow")
    void previewMatchesTheWriteOnCancelledSlots() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        Instant target = base.plus(Duration.ofHours(3));
        UUID cancelled = insertSession(host.getId(), et.getId(), target, target.plus(Duration.ofHours(1)));
        jdbc.update("UPDATE event_sessions SET status = 'CANCELLED' WHERE id = ?", cancelled);

        assertThat(conflictService.check(
                host.getId(), et.getId(), target, target.plus(Duration.ofHours(1)), moving).hasHard())
                .as("a cancelled class does not occupy its hour against the host who cancelled it")
                .isFalse();

        // The write agrees — that is the property under test, not the flag on its own.
        assertThatCode(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("preview reports an exact-start collision with a live session")
    void previewReportsExactStartCollisionWithLiveSession() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        Instant target = base.plus(Duration.ofHours(3));
        insertSession(host.getId(), et.getId(), target, target.plus(Duration.ofHours(1)));

        RescheduleConflicts conflicts = conflictService.check(
                host.getId(), et.getId(), target, target.plus(Duration.ofHours(1)), moving);
        assertThat(conflicts.hasHard()).isTrue();
        assertThat(conflicts.hard()).anySatisfy(conflict ->
                assertThat(conflict.source()).isEqualTo("SLOT_TAKEN"));
    }

    @Test
    @DisplayName("the moving session's own start time is not a collision with itself")
    void previewDoesNotFlagTheMovingSessionAgainstItself() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        // Re-confirming the time it already occupies must stay clean, or the dialog would block
        // the host from confirming the session's current slot.
        assertThat(conflictService.check(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)), moving)
                .hasHard())
                .isFalse();
    }

    // ── Availability must never block ────────────────────────────────────────

    @Test
    @DisplayName("a time outside the host's availability is allowed — the override is the point")
    void outsideAvailabilityIsAllowed() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        // No availability schedule exists at all, so nothing is "available" by rule.
        Instant target = base.plus(Duration.ofDays(3));
        assertThatCode(() -> sessionService.rescheduleSession(moving, host.getId(), target))
                .doesNotThrowAnyException();

        assertThat(jdbc.queryForObject(
                "SELECT start_time FROM event_sessions WHERE id = ?", Timestamp.class, moving)
                .toInstant()).isEqualTo(target);
    }

    // ── Conflict service shape ───────────────────────────────────────────────

    @Test
    @DisplayName("conflict service reports a clean window as unblocked")
    void serviceReportsNoConflicts() {
        User host = createHost();
        Instant base = nextHour().plus(Duration.ofDays(2));

        RescheduleConflicts result = conflictService.check(
                host.getId(), base, base.plus(Duration.ofHours(1)), null);

        assertThat(result.hasHard()).isFalse();
        assertThat(result.hasSoft()).isFalse();
    }

    @Test
    @DisplayName("conflict service names the conflicting commitment")
    void serviceNamesConflict() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        RescheduleConflicts result = conflictService.check(
                host.getId(), base, base.plus(Duration.ofHours(1)), null);

        assertThat(result.hasHard()).isTrue();
        assertThat(result.hard()).hasSize(1);
        assertThat(result.hard().get(0).source()).isEqualTo("GROUP_SESSION");
    }

    @Test
    @DisplayName("excludeSessionId removes the moving session from its own conflict list")
    void serviceExcludesMovingSession() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 10);
        Instant base = nextHour();
        UUID moving = insertSession(host.getId(), et.getId(), base, base.plus(Duration.ofHours(1)));

        assertThat(conflictService.check(host.getId(), base, base.plus(Duration.ofHours(1)), moving)
                .hasHard()).isFalse();
    }

    @Test
    @DisplayName("an invalid interval yields no conflicts rather than throwing")
    void serviceToleratesBadInput() {
        User host = createHost();
        Instant base = nextHour();

        assertThat(conflictService.check(host.getId(), base, base, null).hasHard()).isFalse();
        assertThat(conflictService.check(null, base, base.plus(Duration.ofHours(1)), null).hasHard())
                .isFalse();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private UUID insertSession(UUID hostId, UUID eventTypeId, Instant start, Instant end) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event_sessions
                    (id, host_id, event_type_id, start_time, end_time, status, capacity,
                     confirmed_count, version, calendar_sequence, terminal_intent_epoch,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', 10, 0, 1, 0, 0, NOW(), NOW())
                """, id, hostId, eventTypeId, Timestamp.from(start), Timestamp.from(end));
        return id;
    }

    private UUID insertBooking(UUID hostId, UUID eventTypeId, Instant start, Instant end) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings
                    (id, host_id, event_type_id, start_time, end_time, guest_email, guest_name,
                     status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'guest@test.com', 'Guest', 'CONFIRMED', 1, NOW(), NOW())
                """, id, hostId, eventTypeId, Timestamp.from(start), Timestamp.from(end));
        return id;
    }

    private void insertAssignment(UUID bookingId, UUID participantId, String reason) {
        jdbc.update("""
                INSERT INTO booking_assignments
                    (id, booking_id, participant_user_id, assignment_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(), NOW())
                """, UUID.randomUUID(), bookingId, participantId, reason);
    }
}
