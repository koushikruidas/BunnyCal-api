package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.RegistrationExpiryScheduler;
import io.bunnycal.session.service.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests expiry of PENDING registrations and interaction with confirmation.
 */
class SessionRegistrationExpiryIT extends AbstractSessionIT {

    @Autowired private SessionService sessionService;
    @Autowired private RegistrationExpiryScheduler expiryScheduler;

    @Test
    void confirmAfterExpiry_throwsRegistrationExpired() {
        User host = createHost();
        EventType eventType = createGroupEventType(host.getId(), 5);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        // Join with a hold that expires immediately (1ms in the past after a tiny sleep).
        JoinSessionResult join = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMillis(1));

        // Ensure expiry has elapsed.
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        assertThatThrownBy(() ->
                sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId()))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_EXPIRED));
    }

    @Test
    void expiryScheduler_cancelsPendingExpiredRegistrations() {
        User host = createHost();
        EventType eventType = createGroupEventType(host.getId(), 5);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        // Join with immediate expiry.
        JoinSessionResult join = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 5,
                "guest@test.com", "Guest", Duration.ofMillis(1));

        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        // Run expiry job.
        expiryScheduler.expireOverdueHolds();

        Map<String, Object> reg = queryRegistration(join.registrationId());
        assertThat(reg.get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void expiredRegistration_doesNotConsumeCapacity() {
        User host = createHost();
        int capacity = 1;
        EventType eventType = createGroupEventType(host.getId(), capacity);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        // First attendee joins but never confirms and expires.
        JoinSessionResult joinA = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, capacity,
                "a@test.com", "A", Duration.ofMillis(1));

        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        expiryScheduler.expireOverdueHolds();

        // Session should still be OPEN with confirmed_count=0 (PENDING never consumed a seat).
        Map<String, Object> sessionRow = querySession(joinA.sessionId());
        assertThat(sessionRow.get("status")).isEqualTo("OPEN");
        assertThat(((Number) sessionRow.get("confirmed_count")).intValue()).isZero();

        // Second attendee can now join and confirm successfully.
        JoinSessionResult joinB = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, capacity,
                "b@test.com", "B", Duration.ofMinutes(5));
        sessionService.confirmRegistration(joinB.sessionId(), joinB.registrationId(), host.getId());

        sessionRow = querySession(joinB.sessionId());
        assertThat(((Number) sessionRow.get("confirmed_count")).intValue()).isEqualTo(1);
        assertThat(sessionRow.get("status")).isEqualTo("FULL");
    }
}
