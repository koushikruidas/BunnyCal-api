package io.bunnycal.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.session.dto.SessionDetailResponse;
import io.bunnycal.session.dto.SessionPageResponse;
import io.bunnycal.session.dto.SessionRegistrationPageResponse;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionQueryService;
import io.bunnycal.session.service.SessionService;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SessionDiscoveryIT extends AbstractSessionIT {

    @Autowired private SessionService sessionService;
    @Autowired private SessionQueryService sessionQueryService;
    @Autowired private CalendarSyncJobRepository syncJobRepository;

    @Test
    void hostSessionList_paginatesAndHonorsOwnership() {
        var host = createHost();
        var eventType = createGroupEventType(host.getId(), 2);
        Instant firstStart = nextHour();
        Instant secondStart = firstStart.plusSeconds(3600);

        JoinSessionResult first = sessionService.joinSession(
                host.getId(), eventType.getId(), firstStart, firstStart.plusSeconds(3600), 2,
                "a@test.com", "Alice", null);
        sessionService.confirmRegistration(first.sessionId(), first.registrationId(), host.getId());

        JoinSessionResult second = sessionService.joinSession(
                host.getId(), eventType.getId(), secondStart, secondStart.plusSeconds(3600), 2,
                "b@test.com", "Bob", null);
        sessionService.confirmRegistration(second.sessionId(), second.registrationId(), host.getId());

        SessionPageResponse page = sessionQueryService.listSessionsForHost(
                host.getId(), host.getId(), eventType.getId(), null, null, null, null, null, 1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();

        SessionPageResponse secondPage = sessionQueryService.listSessionsForHost(
                host.getId(), host.getId(), eventType.getId(), null, null, null, null, page.nextCursor(), 1);

        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().get(0).sessionId()).isEqualTo(second.sessionId());
    }

    @Test
    void sessionDetail_includesSyncStateAndAuthorization() {
        var host = createHost();
        var eventType = createGroupEventType(host.getId(), 2);
        Instant start = nextHour();

        JoinSessionResult join = sessionService.joinSession(
                host.getId(), eventType.getId(), start, start.plusSeconds(3600), 2,
                "a@test.com", "Alice", null);
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        long sessionVersion = ((Number) querySession(join.sessionId()).get("version")).longValue();
        inTx(() -> syncJobRepository.upsertPendingJob(
                UUID.randomUUID(),
                "SESSION",
                join.sessionId(),
                "google",
                "UPDATE",
                "ext-123",
                host.getId(),
                null,
                sessionVersion));

        SessionDetailResponse detail = sessionQueryService.getSessionDetail(host.getId(), join.sessionId());

        assertThat(detail.sessionId()).isEqualTo(join.sessionId());
        assertThat(detail.sync().externalEventId()).isEqualTo("ext-123");
        assertThat(detail.sync().provider()).isEqualTo("google");
        assertThat(detail.sync().stale()).isFalse();

        assertThatThrownBy(() -> sessionQueryService.getSessionDetail(UUID.randomUUID(), join.sessionId()))
                .isInstanceOf(io.bunnycal.common.exception.CustomException.class)
                .satisfies(ex -> assertThat(((io.bunnycal.common.exception.CustomException) ex).getErrorCode().getCode())
                        .isEqualTo("FORBIDDEN"));
    }

    @Test
    void registrationList_paginatesAndMarksExpiredPending() {
        var host = createHost();
        var eventType = createGroupEventType(host.getId(), 2);
        Instant start = nextHour();

        JoinSessionResult first = sessionService.joinSession(
                host.getId(), eventType.getId(), start, start.plusSeconds(3600), 2,
                "a@test.com", "Alice", null);
        sessionService.confirmRegistration(first.sessionId(), first.registrationId(), host.getId());

        JoinSessionResult second = sessionService.joinSession(
                host.getId(), eventType.getId(), start, start.plusSeconds(3600), 2,
                "b@test.com", "Bob", null);

        SessionRegistrationPageResponse page = sessionQueryService.listRegistrations(
                host.getId(), first.sessionId(), null, null, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();

        SessionRegistrationPageResponse secondPage = sessionQueryService.listRegistrations(
                host.getId(), first.sessionId(), null, page.nextCursor(), 1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().get(0).registrationId()).isEqualTo(second.registrationId());
        assertThat(secondPage.items().get(0).expired()).isFalse();
    }
}
