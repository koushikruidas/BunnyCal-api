package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.session.service.ConfirmRegistrationResult;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.MoveRegistrationResult;
import io.bunnycal.session.service.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guest self-reschedule for group events: moving a registration between sessions.
 *
 * <p>Previously blocked outright ("cancel your registration and re-book"), which lost
 * the seat the moment a guest considered moving. These tests cover the seat accounting
 * across both sessions, the boundary rules on the target, and the concurrency shape —
 * including two guests swapping sessions in opposite directions, which is where an
 * inconsistent lock order would deadlock.
 */
class GuestSessionRescheduleIT extends AbstractSessionIT {

    @Autowired private SessionService sessionService;

    /** Joins and confirms a guest, returning the registration id. */
    private UUID confirmedGuest(User host, EventType group, Instant start, String email) {
        JoinSessionResult joined = sessionService.joinSession(
                host.getId(), group.getId(), start, start.plus(1, ChronoUnit.HOURS),
                group.getCapacity(), email, email, Duration.ofMinutes(15));
        ConfirmRegistrationResult confirmed = sessionService.confirmRegistration(
                joined.sessionId(), joined.registrationId(), host.getId());
        return confirmed.registrationId();
    }

    /** The shared helper omits session_id, which is the column these tests care about. */
    private UUID sessionIdOf(UUID registrationId) {
        return jdbc.queryForObject(
                "SELECT session_id FROM session_registrations WHERE id = ?",
                UUID.class, registrationId);
    }

    private Map<String, Object> sessionAt(UUID hostId, UUID eventTypeId, Instant start) {
        return jdbc.queryForMap("""
                SELECT id, status, confirmed_count FROM event_sessions
                WHERE host_id = ? AND event_type_id = ? AND start_time = ?
                """, hostId, eventTypeId, java.sql.Timestamp.from(start));
    }

    @Test
    void movingAGuest_transfersTheSeatBetweenSessions() {
        User host = createHost();
        EventType group = createGroupEventType(host.getId(), 10);
        Instant monday = nextHour().plus(1, ChronoUnit.DAYS);
        Instant tuesday = monday.plus(1, ChronoUnit.DAYS);

        confirmedGuest(host, group, monday, "alice@test.com");
        confirmedGuest(host, group, monday, "bob@test.com");
        UUID charlie = confirmedGuest(host, group, monday, "charlie@test.com");

        MoveRegistrationResult result =
                sessionService.moveRegistration(charlie, host.getId(), tuesday, null);

        assertThat(sessionAt(host.getId(), group.getId(), monday).get("confirmed_count")).isEqualTo(2);
        assertThat(sessionAt(host.getId(), group.getId(), tuesday).get("confirmed_count")).isEqualTo(1);
        assertThat(result.startTime()).isEqualTo(tuesday);

        // The registration itself moved rather than being cancelled and recreated —
        // its id is stable, so the guest's manage link keeps working.
        Map<String, Object> reg = queryRegistration(charlie);
        assertThat(reg.get("status")).isEqualTo("CONFIRMED");
        assertThat(sessionIdOf(charlie)).isEqualTo(result.targetSessionId());
    }

    @Test
    void movingIntoAFullSession_isRejectedAndLeavesTheOriginalSeatIntact() {
        User host = createHost();
        EventType group = createGroupEventType(host.getId(), 1);
        Instant monday = nextHour().plus(1, ChronoUnit.DAYS);
        Instant tuesday = monday.plus(1, ChronoUnit.DAYS);

        UUID alice = confirmedGuest(host, group, monday, "alice@test.com");
        confirmedGuest(host, group, tuesday, "bob@test.com"); // fills Tuesday (capacity 1)

        assertThatThrownBy(() -> sessionService.moveRegistration(alice, host.getId(), tuesday, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_CAPACITY_FULL);

        // Critically: the guest still holds their original seat. Releasing it first
        // would have dropped them from both sessions.
        assertThat(sessionAt(host.getId(), group.getId(), monday).get("confirmed_count")).isEqualTo(1);
        assertThat(queryRegistration(alice).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void movingToAStartedSession_isRejected() {
        User host = createHost();
        EventType group = createGroupEventType(host.getId(), 10);
        Instant future = nextHour().plus(1, ChronoUnit.DAYS);
        UUID alice = confirmedGuest(host, group, future, "alice@test.com");

        assertThatThrownBy(() -> sessionService.moveRegistration(
                alice, host.getId(), Instant.now().minus(1, ChronoUnit.HOURS), null))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("must be in the future");
    }

    @Test
    void movingToTheSameTime_isRejected() {
        User host = createHost();
        EventType group = createGroupEventType(host.getId(), 10);
        Instant start = nextHour().plus(1, ChronoUnit.DAYS);
        UUID alice = confirmedGuest(host, group, start, "alice@test.com");

        assertThatThrownBy(() -> sessionService.moveRegistration(alice, host.getId(), start, null))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("matches the current one");
    }

    @Test
    void movingAnUnconfirmedHold_isRejected() {
        User host = createHost();
        EventType group = createGroupEventType(host.getId(), 10);
        Instant monday = nextHour().plus(1, ChronoUnit.DAYS);

        // PENDING: held but never confirmed, so it reserves no seat to transfer.
        JoinSessionResult held = sessionService.joinSession(
                host.getId(), group.getId(), monday, monday.plus(1, ChronoUnit.HOURS),
                10, "alice@test.com", "Alice", Duration.ofMinutes(15));

        assertThatThrownBy(() -> sessionService.moveRegistration(
                held.registrationId(), host.getId(), monday.plus(1, ChronoUnit.DAYS), null))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Only confirmed registrations");
    }

    @Test
    void movingToACancelledSession_isRejected() {
        User host = createHost();
        EventType group = createGroupEventType(host.getId(), 10);
        Instant monday = nextHour().plus(1, ChronoUnit.DAYS);
        Instant tuesday = monday.plus(1, ChronoUnit.DAYS);

        UUID alice = confirmedGuest(host, group, monday, "alice@test.com");
        UUID bobReg = confirmedGuest(host, group, tuesday, "bob@test.com");
        UUID tuesdaySessionId = sessionIdOf(bobReg);
        sessionService.cancelSession(tuesdaySessionId, host.getId());

        assertThatThrownBy(() -> sessionService.moveRegistration(alice, host.getId(), tuesday, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_CANCELLED);
    }

    @Test
    void concurrentMovesIntoASingleSeat_admitExactlyOne() throws Exception {
        User host = createHost();
        EventType group = createGroupEventType(host.getId(), 1);
        Instant slotA = nextHour().plus(1, ChronoUnit.DAYS);
        Instant slotB = slotA.plus(1, ChronoUnit.DAYS);
        Instant target = slotA.plus(2, ChronoUnit.DAYS);

        // Two guests in separate sessions, both trying to move into the same
        // single-seat target.
        UUID alice = confirmedGuest(host, group, slotA, "alice@test.com");
        UUID bob = confirmedGuest(host, group, slotB, "bob@test.com");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (UUID registrationId : List.of(alice, bob)) {
            pool.submit(() -> {
                try {
                    start.await();
                    sessionService.moveRegistration(registrationId, host.getId(), target, null);
                    succeeded.incrementAndGet();
                } catch (CustomException e) {
                    rejected.incrementAndGet();
                } catch (Exception ignored) {
                    // Serialization failures surface as rejections for this assertion.
                    rejected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(sessionAt(host.getId(), group.getId(), target).get("confirmed_count")).isEqualTo(1);
    }

    @Test
    void guestsSwappingSessionsInOppositeDirections_bothComplete() throws Exception {
        User host = createHost();
        EventType group = createGroupEventType(host.getId(), 10);
        Instant slotA = nextHour().plus(1, ChronoUnit.DAYS);
        Instant slotB = slotA.plus(1, ChronoUnit.DAYS);

        UUID alice = confirmedGuest(host, group, slotA, "alice@test.com");
        UUID bob = confirmedGuest(host, group, slotB, "bob@test.com");

        // Alice A→B while Bob goes B→A. With per-session locks taken in call order
        // rather than a fixed global order, each thread would hold the lock the other
        // needs. Ascending start-time ordering makes that unreachable.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<?> aliceMove = pool.submit(() -> {
            await(start);
            sessionService.moveRegistration(alice, host.getId(), slotB, null);
        });
        Future<?> bobMove = pool.submit(() -> {
            await(start);
            sessionService.moveRegistration(bob, host.getId(), slotA, null);
        });

        start.countDown();
        pool.shutdown();
        // A deadlock shows up here as a timeout rather than an exception.
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        aliceMove.get();
        bobMove.get();

        assertThat(sessionIdOf(alice))
                .isEqualTo(sessionAt(host.getId(), group.getId(), slotB).get("id"));
        assertThat(sessionIdOf(bob))
                .isEqualTo(sessionAt(host.getId(), group.getId(), slotA).get("id"));
        // Seats balance out: one guest in each session, same as before the swap.
        assertThat(sessionAt(host.getId(), group.getId(), slotA).get("confirmed_count")).isEqualTo(1);
        assertThat(sessionAt(host.getId(), group.getId(), slotB).get("confirmed_count")).isEqualTo(1);
    }

    @Test
    void movingOutOfAFullSession_reopensIt() {
        User host = createHost();
        EventType group = createGroupEventType(host.getId(), 1);
        Instant monday = nextHour().plus(1, ChronoUnit.DAYS);
        Instant tuesday = monday.plus(1, ChronoUnit.DAYS);

        UUID alice = confirmedGuest(host, group, monday, "alice@test.com");
        assertThat(sessionAt(host.getId(), group.getId(), monday).get("status")).isEqualTo("FULL");

        sessionService.moveRegistration(alice, host.getId(), tuesday, null);

        // The vacated seat must become bookable again, not stay FULL.
        assertThat(sessionAt(host.getId(), group.getId(), monday).get("status")).isEqualTo("OPEN");
        assertThat(sessionAt(host.getId(), group.getId(), monday).get("confirmed_count")).isEqualTo(0);
        assertThat(sessionAt(host.getId(), group.getId(), tuesday).get("status")).isEqualTo("FULL");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
