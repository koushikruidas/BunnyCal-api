package io.bunnycal.session.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.session.dto.SessionDetailResponse;
import io.bunnycal.session.dto.SessionPageResponse;
import io.bunnycal.session.dto.SessionRegistrationPageResponse;
import io.bunnycal.session.dto.SessionSyncStatusResponse;
import io.bunnycal.session.service.SessionService;
import io.bunnycal.session.service.SessionQueryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class SessionControllerTest {

    @Mock private SessionQueryService sessionQueryService;
    @Mock private SessionService sessionService;
    @Mock private io.bunnycal.session.service.SessionSeriesService sessionSeriesService;
    @Mock private io.bunnycal.session.service.RescheduleConflictService rescheduleConflictService;

    private SessionController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new SessionController(sessionQueryService, sessionService, sessionSeriesService,
                rescheduleConflictService);
    }

    @Test
    void cancelSession_passesThroughToServiceAndReturnsFreshDetail() {
        UUID hostId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(hostId, null);
        SessionDetailResponse detail = new SessionDetailResponse(
                sessionId, hostId, UUID.randomUUID(), "Group Workshop", "group-workshop",
                Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T11:00:00Z"),
                "CANCELLED", 3, 0, 0, 3, 0, 0d, 2L, 1L,
                Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-02T00:00:00Z"),
                false, SessionSyncStatusResponse.empty(false));
        when(sessionQueryService.getSessionDetail(hostId, sessionId)).thenReturn(detail);

        var body = controller.cancelSession(authentication, sessionId).getBody();

        assertEquals(detail, body.getData());
        verify(sessionService).cancelSession(sessionId, hostId);
        verify(sessionQueryService, times(2)).getSessionDetail(hostId, sessionId);
    }

    @Test
    void removeAttendee_passesThroughToServiceAndReturnsFreshDetail() {
        UUID hostId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(hostId, null);
        SessionDetailResponse detail = new SessionDetailResponse(
                sessionId, hostId, UUID.randomUUID(), "Group Workshop", "group-workshop",
                Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T11:00:00Z"),
                "OPEN", 3, 1, 0, 1, 0, 33.3333, 2L, 1L,
                Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-02T00:00:00Z"),
                false, SessionSyncStatusResponse.empty(false));
        when(sessionQueryService.getSessionDetail(hostId, sessionId)).thenReturn(detail);

        var body = controller.removeAttendee(authentication, sessionId, registrationId).getBody();

        assertEquals(detail, body.getData());
        verify(sessionService).cancelRegistration(sessionId, registrationId, hostId, null);
        verify(sessionQueryService, times(2)).getSessionDetail(hostId, sessionId);
    }

    @Test
    void listRegistrations_passesThroughToService() {
        UUID hostId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(hostId, null);
        SessionRegistrationPageResponse page = new SessionRegistrationPageResponse(List.of(), null, false);
        when(sessionQueryService.listRegistrations(eq(hostId), eq(sessionId), eq("CONFIRMED"), eq("cursor"), eq(5)))
                .thenReturn(page);

        var body = controller.listRegistrations(authentication, sessionId, io.bunnycal.session.domain.RegistrationStatus.CONFIRMED,
                "cursor", 5).getBody();

        assertEquals(page, body.getData());
        verify(sessionQueryService).listRegistrations(eq(hostId), eq(sessionId), eq("CONFIRMED"), eq("cursor"), eq(5));
    }

    @Test
    void getSession_usesAuthenticatedUser() {
        UUID hostId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(hostId, null);
        SessionDetailResponse detail = new SessionDetailResponse(
                sessionId, hostId, UUID.randomUUID(), "Group Workshop", "group-workshop",
                Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T11:00:00Z"),
                "OPEN", 3, 1, 0, 1, 0, 33.3333, 1L, 0L,
                Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-02T00:00:00Z"),
                false, SessionSyncStatusResponse.empty(false));
        when(sessionQueryService.getSessionDetail(hostId, sessionId)).thenReturn(detail);

        var body = controller.getSession(authentication, sessionId).getBody();
        assertEquals(detail, body.getData());
        verify(sessionQueryService).getSessionDetail(hostId, sessionId);
    }
}
