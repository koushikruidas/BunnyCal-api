package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies confirmed_count stays consistent across cancellation and session cancel scenarios.
 */
class SessionCountConsistencyIT extends AbstractSessionIT {

    @Autowired private SessionService sessionService;

    @Test
    void cancelConfirmedRegistration_decrementsCount_andReopensFullSession() {
        User host = createHost();
        int capacity = 2;
        EventType eventType = createGroupEventType(host.getId(), capacity);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        // Fill session to capacity.
        JoinSessionResult joinA = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, capacity,
                "a@test.com", "A", Duration.ofMinutes(5));
        JoinSessionResult joinB = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, capacity,
                "b@test.com", "B", Duration.ofMinutes(5));

        sessionService.confirmRegistration(joinA.sessionId(), joinA.registrationId(), host.getId());
        sessionService.confirmRegistration(joinB.sessionId(), joinB.registrationId(), host.getId());

        Map<String, Object> row = querySession(joinA.sessionId());
        assertThat(row.get("status")).isEqualTo("FULL");
        assertThat(((Number) row.get("confirmed_count")).intValue()).isEqualTo(2);

        // Cancel one confirmed registration.
        sessionService.cancelRegistration(joinA.sessionId(), joinA.registrationId(), host.getId(), null);

        row = querySession(joinA.sessionId());
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(((Number) row.get("confirmed_count")).intValue()).isEqualTo(1);

        // DB count matches stored counter.
        assertThat(countRegistrationsByStatus(joinA.sessionId(), "CONFIRMED")).isEqualTo(1);
    }

    @Test
    void cancelPendingRegistration_doesNotChangeCount() {
        User host = createHost();
        EventType eventType = createGroupEventType(host.getId(), 5);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult join = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 5,
                "pending@test.com", "P", Duration.ofMinutes(5));

        // Cancel before confirming.
        sessionService.cancelRegistration(join.sessionId(), join.registrationId(), host.getId(), null);

        Map<String, Object> row = querySession(join.sessionId());
        assertThat(((Number) row.get("confirmed_count")).intValue()).isZero();
        assertThat(row.get("status")).isEqualTo("OPEN");
    }

    @Test
    void cancelSession_zerosConfirmedCountAndCancelsAllRegistrations() {
        User host = createHost();
        int capacity = 3;
        EventType eventType = createGroupEventType(host.getId(), capacity);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult joinA = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, capacity,
                "a@test.com", "A", Duration.ofMinutes(5));
        JoinSessionResult joinB = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, capacity,
                "b@test.com", "B", Duration.ofMinutes(5));
        JoinSessionResult joinC = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, capacity,
                "c@test.com", "C", Duration.ofMinutes(5));

        sessionService.confirmRegistration(joinA.sessionId(), joinA.registrationId(), host.getId());
        sessionService.confirmRegistration(joinB.sessionId(), joinB.registrationId(), host.getId());
        // C stays PENDING.

        Map<String, Object> before = querySession(joinA.sessionId());
        assertThat(((Number) before.get("confirmed_count")).intValue()).isEqualTo(2);

        // Host cancels session.
        sessionService.cancelSession(joinA.sessionId(), host.getId());

        Map<String, Object> after = querySession(joinA.sessionId());
        assertThat(after.get("status")).isEqualTo("CANCELLED");
        assertThat(((Number) after.get("confirmed_count")).intValue()).isZero();

        // All registrations cancelled.
        assertThat(countRegistrationsByStatus(joinA.sessionId(), "CANCELLED")).isEqualTo(3);
        assertThat(countRegistrationsByStatus(joinA.sessionId(), "CONFIRMED")).isZero();
        assertThat(countRegistrationsByStatus(joinA.sessionId(), "PENDING")).isZero();
    }

    @Test
    void cancelSession_idempotent() {
        User host = createHost();
        EventType eventType = createGroupEventType(host.getId(), 2);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult join = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 2,
                "guest@test.com", "G", Duration.ofMinutes(5));
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        sessionService.cancelSession(join.sessionId(), host.getId());
        // Second cancel must not throw.
        sessionService.cancelSession(join.sessionId(), host.getId());

        Map<String, Object> row = querySession(join.sessionId());
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(((Number) row.get("confirmed_count")).intValue()).isZero();
    }

    @Test
    void multipleJoinConfirmCancel_countRemainsAccurate() {
        User host = createHost();
        int capacity = 5;
        EventType eventType = createGroupEventType(host.getId(), capacity);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        // 5 attendees join and confirm.
        JoinSessionResult[] joins = new JoinSessionResult[capacity];
        for (int i = 0; i < capacity; i++) {
            joins[i] = sessionService.joinSession(
                    host.getId(), eventType.getId(), start, end, capacity,
                    "attendee-" + i + "@test.com", "A" + i, Duration.ofMinutes(5));
            sessionService.confirmRegistration(joins[i].sessionId(), joins[i].registrationId(), host.getId());
        }

        UUID sessionId = joins[0].sessionId();
        assertThat(((Number) querySession(sessionId).get("confirmed_count")).intValue()).isEqualTo(5);
        assertThat(querySession(sessionId).get("status")).isEqualTo("FULL");

        // 2 attendees cancel.
        sessionService.cancelRegistration(sessionId, joins[0].registrationId(), host.getId(), null);
        sessionService.cancelRegistration(sessionId, joins[1].registrationId(), host.getId(), null);

        assertThat(((Number) querySession(sessionId).get("confirmed_count")).intValue()).isEqualTo(3);
        assertThat(querySession(sessionId).get("status")).isEqualTo("OPEN");
        assertThat(countRegistrationsByStatus(sessionId, "CONFIRMED")).isEqualTo(3);
        assertThat(countRegistrationsByStatus(sessionId, "CANCELLED")).isEqualTo(2);
    }
}
