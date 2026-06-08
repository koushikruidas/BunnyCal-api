package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.session.dto.SessionPageResponse;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionQueryService;
import io.bunnycal.session.service.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that GET /api/sessions/me (listSessionsForHost) excludes sessions
 * where every registration has been cancelled (confirmedCount=0, pendingCount=0),
 * while sessions with at least one PENDING or CONFIRMED registration are still shown.
 *
 * The event-type-scoped view (listSessionsForEventType) intentionally shows all
 * sessions regardless of participation — those cases are not tested here.
 */
class SessionDashboardFilterIT extends AbstractSessionIT {

    @Autowired private SessionService sessionService;
    @Autowired private SessionQueryService sessionQueryService;

    // ── 1: all-cancelled → not shown ─────────────────────────────────────────

    // A session whose only registration was cancelled must NOT appear in the
    // Meetings dashboard.  Before the fix, registrationCount=1 (COUNT(*) includes
    // CANCELLED rows) caused the session to show with confirmedCount=0, pendingCount=0.
    @Test
    void allCancelledRegistrations_sessionExcludedFromDashboard() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 5);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult hold = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMinutes(5));

        // Cancel the registration directly so no other participants exist.
        jdbc.update("UPDATE session_registrations SET status = 'CANCELLED' WHERE id = ?",
                hold.registrationId());

        SessionPageResponse page = sessionQueryService.listSessionsForHost(
                host.getId(), host.getId(), et.getId(), null, null, null, null, null, 25);

        assertThat(page.items())
                .as("session with only cancelled registrations must not appear on dashboard")
                .noneMatch(s -> s.sessionId().equals(hold.sessionId()));
    }

    // ── 2: one PENDING hold → shown ──────────────────────────────────────────

    // A session with at least one PENDING registration must appear, even when
    // confirmedCount is still 0.
    @Test
    void pendingRegistration_sessionIncludedInDashboard() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 5);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult hold = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMinutes(5));
        // Do NOT confirm — leave as PENDING.

        SessionPageResponse page = sessionQueryService.listSessionsForHost(
                host.getId(), host.getId(), et.getId(), null, null, null, null, null, 25);

        assertThat(page.items())
                .as("session with a PENDING registration must appear on dashboard")
                .anyMatch(s -> s.sessionId().equals(hold.sessionId()));

        assertThat(page.items().stream()
                .filter(s -> s.sessionId().equals(hold.sessionId()))
                .findFirst()
                .orElseThrow()
                .pendingCount())
                .isEqualTo(1);
    }

    // ── 3: confirmed registration → shown ────────────────────────────────────

    // A session with at least one CONFIRMED registration must appear.
    @Test
    void confirmedRegistration_sessionIncludedInDashboard() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 5);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult hold = sessionService.joinSession(
                host.getId(), et.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMinutes(5));
        sessionService.confirmRegistration(hold.sessionId(), hold.registrationId(), host.getId());

        SessionPageResponse page = sessionQueryService.listSessionsForHost(
                host.getId(), host.getId(), et.getId(), null, null, null, null, null, 25);

        assertThat(page.items())
                .as("session with a CONFIRMED registration must appear on dashboard")
                .anyMatch(s -> s.sessionId().equals(hold.sessionId()));

        assertThat(page.items().stream()
                .filter(s -> s.sessionId().equals(hold.sessionId()))
                .findFirst()
                .orElseThrow()
                .confirmedCount())
                .isEqualTo(1);
    }

    // ── 4: mixed sessions, only active ones returned, cursor unaffected ───────

    // With two sessions — one with only cancelled registrations, one with a
    // PENDING registration — the dashboard must return exactly one session and
    // report hasMore=false (pagination cursor must not be corrupted by the filter).
    @Test
    void mixedSessions_onlyActiveReturned_paginationIntact() {
        User host = createHost();
        EventType et = createGroupEventType(host.getId(), 5);
        Instant start1 = nextHour();
        Instant start2 = start1.plus(Duration.ofHours(2));
        Duration dur = Duration.ofHours(1);

        // Session 1: all registrations cancelled → must be hidden.
        JoinSessionResult s1 = sessionService.joinSession(
                host.getId(), et.getId(), start1, start1.plus(dur), 5,
                "ghost@test.com", "Ghost", Duration.ofMinutes(5));
        jdbc.update("UPDATE session_registrations SET status = 'CANCELLED' WHERE id = ?",
                s1.registrationId());

        // Session 2: live PENDING hold → must appear.
        JoinSessionResult s2 = sessionService.joinSession(
                host.getId(), et.getId(), start2, start2.plus(dur), 5,
                "real@test.com", "Real", Duration.ofMinutes(5));

        SessionPageResponse page = sessionQueryService.listSessionsForHost(
                host.getId(), host.getId(), et.getId(), null, null, null, null, null, 25);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).sessionId()).isEqualTo(s2.sessionId());
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }
}
