package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.exception.RegistrationHoldActiveException;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the inline-expiry path introduced in joinSession.
 *
 * Before the fix, an expired-but-not-yet-reaped PENDING hold kept the partial
 * unique index slot occupied, causing re-booking attempts to hit a duplicate-key
 * constraint error rather than returning a clean ALREADY_REGISTERED or succeeding.
 *
 * After the fix, joinSession performs an application-level expiry check against
 * expires_at using TimeSource.now() and cancels the stale hold inline before
 * inserting the new registration. The background reaper remains, but is only
 * responsible for table hygiene — not for unblocking re-registration.
 */
class RegistrationRebookAfterExpiryIT extends AbstractSessionIT {

    @Autowired private SessionService sessionService;

    // ── A: expired PENDING hold, reaper has NOT run ───────────────────────────

    // An expired PENDING hold (status still PENDING, expires_at in the past) must
    // not block a new booking attempt. joinSession must cancel the stale hold
    // inline and create a fresh PENDING registration without a scheduler cycle.
    @Test
    void expiredPendingHold_reaperNotRun_rebookSucceeds() throws InterruptedException {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 5);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        // First hold: expires in 1 ms.
        JoinSessionResult first = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMillis(1));

        // Wait for the hold to expire; do NOT run the reaper.
        Thread.sleep(20);

        // Verify the row is still PENDING (reaper has not run).
        assertThat(queryRegistration(first.registrationId()).get("status")).isEqualTo("PENDING");

        // Re-book: must succeed by cancelling the stale hold inline.
        JoinSessionResult second = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMinutes(5));

        assertThat(second.registrationId()).isNotEqualTo(first.registrationId());
        assertThat(queryRegistration(second.registrationId()).get("status")).isEqualTo("PENDING");

        // The stale hold must now be CANCELLED.
        assertThat(queryRegistration(first.registrationId()).get("status")).isEqualTo("CANCELLED");

        // Exactly one non-cancelled registration exists for this guest.
        int active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_registrations WHERE session_id = ? AND guest_email = ? AND status != 'CANCELLED'",
                Integer.class, first.sessionId(), "guest@test.com");
        assertThat(active).isEqualTo(1);
    }

    // ── B: live PENDING hold returns REGISTRATION_HOLD_ACTIVE with expiresAt ──

    // A PENDING hold whose expires_at is still in the future must return
    // REGISTRATION_HOLD_ACTIVE (not ALREADY_REGISTERED) and carry the exact
    // expiresAt timestamp from the existing hold row.
    @Test
    void livePendingHold_returnsHoldActiveWithExpiresAt() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 5);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        // First hold: 5-minute window — definitely still live.
        JoinSessionResult first = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMinutes(5));

        // Second attempt must throw RegistrationHoldActiveException.
        assertThatThrownBy(() -> sessionService.joinSession(
                host.getId(), et.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMinutes(5)))
                .isInstanceOf(RegistrationHoldActiveException.class)
                .satisfies(ex -> {
                    RegistrationHoldActiveException holdEx = (RegistrationHoldActiveException) ex;
                    assertThat(holdEx.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_HOLD_ACTIVE);
                    // expiresAt must match the stored hold's expires_at, not a new future time.
                    assertThat(holdEx.getExpiresAt()).isEqualTo(first.expiresAt());
                    assertThat(holdEx.getExpiresAt()).isAfter(Instant.now());
                });
    }

    // ── C: CONFIRMED registration returns ALREADY_REGISTERED ─────────────────

    // A CONFIRMED registration — even one with a past expires_at — must return
    // ALREADY_REGISTERED. Expiry is only meaningful for PENDING holds.
    @Test
    void confirmedRegistration_withPastExpiresAt_returnsAlreadyRegistered() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 5);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        // Hold and immediately confirm.
        JoinSessionResult hold = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMinutes(5));
        sessionService.confirmRegistration(hold.sessionId(), hold.registrationId(), host.getId());

        // Manually backdate expires_at on the CONFIRMED row to simulate a past value.
        jdbc.update(
                "UPDATE session_registrations SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(300)), hold.registrationId());

        // Re-book attempt must be ALREADY_REGISTERED, not REGISTRATION_HOLD_ACTIVE.
        assertThatThrownBy(() -> sessionService.joinSession(
                host.getId(), et.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMinutes(5)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ALREADY_REGISTERED));
    }

    // ── D: concurrent re-book after expiry ───────────────────────────────────

    // Two threads race to re-book the same expired hold. Only one must succeed;
    // the other must either get ALREADY_REGISTERED or also succeed (if the
    // advisory lock serializes them). Either way: exactly one non-cancelled
    // registration for the guest must exist, and capacity must stay correct.
    @Test
    void concurrentRebookAfterExpiry_exactlyOneRegistrationCreated() throws Exception {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 5);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        // Expired hold — reaper has NOT run.
        sessionService.joinSession(
                host.getId(), et.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMillis(1));
        Thread.sleep(20);

        int threads = 10;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        List<Future<UUID>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    JoinSessionResult r = sessionService.joinSession(
                            host.getId(), et.getId(), start, end, 5,
                            "guest@test.com", "Guest", Duration.ofMinutes(5));
                    successes.incrementAndGet();
                    return r.registrationId();
                } catch (CustomException e) {
                    rejections.incrementAndGet();
                    return null;
                }
            }));
        }

        ready.await();
        go.countDown();

        List<UUID> created = new ArrayList<>();
        for (Future<UUID> f : futures) {
            UUID id = f.get();
            if (id != null) created.add(id);
        }
        pool.shutdown();

        // Every thread either succeeded or was rejected — no unhandled exceptions.
        assertThat(successes.get() + rejections.get()).isEqualTo(threads);

        // Exactly one non-cancelled registration must exist for this guest.
        UUID sessionId = (UUID) jdbc.queryForObject(
                "SELECT session_id FROM session_registrations WHERE id = ? LIMIT 1",
                UUID.class, created.stream().findFirst().orElseThrow());
        int activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_registrations WHERE session_id = ? AND guest_email = ? AND status != 'CANCELLED'",
                Integer.class, sessionId, "guest@test.com");
        assertThat(activeCount).isEqualTo(1);

        // confirmed_count must be 0 — nobody confirmed.
        Map<String, Object> sessionRow = querySession(sessionId);
        assertThat(((Number) sessionRow.get("confirmed_count")).intValue()).isZero();
    }

    // ── E: no duplicate registrations, capacity accounting correct ───────────

    // After an expired hold is re-booked and confirmed, capacity accounting must
    // be correct: the expired PENDING row must never have incremented confirmed_count.
    @Test
    void expiredHoldRebookedAndConfirmed_capacityAccountingCorrect() throws InterruptedException {
        User host = createHost();
        int capacity = 2;
        EventType et = createGroupEventType(host.getId(), capacity);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        // Guest A: expired hold, reaper not run.
        JoinSessionResult expiredHold = sessionService.joinSession(
                host.getId(), et.getId(), start, end, capacity,
                "a@test.com", "A", Duration.ofMillis(1));
        Thread.sleep(20);

        // Guest B: live hold, then confirm.
        JoinSessionResult holdB = sessionService.joinSession(
                host.getId(), et.getId(), start, end, capacity,
                "b@test.com", "B", Duration.ofMinutes(5));
        sessionService.confirmRegistration(holdB.sessionId(), holdB.registrationId(), host.getId());

        // Guest A re-books after expiry.
        JoinSessionResult rebookedA = sessionService.joinSession(
                host.getId(), et.getId(), start, end, capacity,
                "a@test.com", "A", Duration.ofMinutes(5));
        sessionService.confirmRegistration(rebookedA.sessionId(), rebookedA.registrationId(), host.getId());

        // confirmed_count must be exactly 2 (capacity), not 3.
        Map<String, Object> sessionRow = querySession(holdB.sessionId());
        assertThat(((Number) sessionRow.get("confirmed_count")).intValue()).isEqualTo(2);
        assertThat(sessionRow.get("status")).isEqualTo("FULL");

        // The expired hold row must be CANCELLED.
        assertThat(queryRegistration(expiredHold.registrationId()).get("status")).isEqualTo("CANCELLED");

        // Exactly 2 CONFIRMED rows.
        assertThat(countRegistrationsByStatus(holdB.sessionId(), "CONFIRMED")).isEqualTo(2);
    }
}
