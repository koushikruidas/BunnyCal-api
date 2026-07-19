package io.bunnycal.session.controller;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.booking.dto.PublicRescheduleRequest;
import io.bunnycal.session.domain.RegistrationStatus;
import io.bunnycal.session.domain.SessionStatus;
import io.bunnycal.session.dto.SessionDetailResponse;
import io.bunnycal.session.dto.SessionPageResponse;
import io.bunnycal.session.dto.SessionRegistrationPageResponse;
import io.bunnycal.session.service.SessionService;
import io.bunnycal.session.service.SessionQueryService;
import io.bunnycal.sync.state.SyncJobStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SessionController {

    private final SessionQueryService sessionQueryService;
    private final SessionService sessionService;

    public SessionController(SessionQueryService sessionQueryService, SessionService sessionService) {
        this.sessionQueryService = sessionQueryService;
        this.sessionService = sessionService;
    }

    @GetMapping("/event-types/{eventTypeId}/sessions")
    public ResponseEntity<ApiResponse<SessionPageResponse>> listEventTypeSessions(
            Authentication authentication,
            @PathVariable UUID eventTypeId,
            @RequestParam(name = "status", required = false) SessionStatus status,
            @RequestParam(name = "syncStatus", required = false) SyncJobStatus syncStatus,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        UUID requesterId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(sessionQueryService.listSessionsForEventType(
                requesterId, eventTypeId, status, from, to, syncStatus, cursor, limit)));
    }

    @GetMapping("/sessions/me")
    public ResponseEntity<ApiResponse<SessionPageResponse>> listMySessions(
            Authentication authentication,
            @RequestParam(name = "eventTypeId", required = false) UUID eventTypeId,
            @RequestParam(name = "status", required = false) SessionStatus status,
            @RequestParam(name = "syncStatus", required = false) SyncJobStatus syncStatus,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        UUID requesterId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(sessionQueryService.listSessionsForHost(
                requesterId, requesterId, eventTypeId, status, from, to, syncStatus, cursor, limit)));
    }

    @PostMapping("/sessions/{sessionId}/cancel")
    public ResponseEntity<ApiResponse<SessionDetailResponse>> cancelSession(
            Authentication authentication,
            @PathVariable UUID sessionId) {
        UUID requesterId = extractUserId(authentication);
        sessionQueryService.getSessionDetail(requesterId, sessionId);
        sessionService.cancelSession(sessionId, requesterId);
        return ResponseEntity.ok(ApiResponse.success(sessionQueryService.getSessionDetail(requesterId, sessionId)));
    }

    @PostMapping("/sessions/{sessionId}/reschedule")
    public ResponseEntity<ApiResponse<SessionDetailResponse>> rescheduleSession(
            Authentication authentication,
            @PathVariable UUID sessionId,
            @RequestBody PublicRescheduleRequest request) {
        if (request == null || request.startTime() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime is required.");
        }
        UUID requesterId = extractUserId(authentication);
        sessionQueryService.getSessionDetail(requesterId, sessionId);
        sessionService.rescheduleSession(sessionId, requesterId, request.startTime());
        return ResponseEntity.ok(ApiResponse.success(sessionQueryService.getSessionDetail(requesterId, sessionId)));
    }

    @DeleteMapping("/sessions/{sessionId}/registrations/{registrationId}")
    public ResponseEntity<ApiResponse<SessionDetailResponse>> removeAttendee(
            Authentication authentication,
            @PathVariable UUID sessionId,
            @PathVariable UUID registrationId) {
        UUID requesterId = extractUserId(authentication);
        sessionQueryService.getSessionDetail(requesterId, sessionId);
        sessionService.cancelRegistration(sessionId, registrationId, requesterId, null);
        return ResponseEntity.ok(ApiResponse.success(sessionQueryService.getSessionDetail(requesterId, sessionId)));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<SessionDetailResponse>> getSession(
            Authentication authentication,
            @PathVariable UUID sessionId) {
        UUID requesterId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(sessionQueryService.getSessionDetail(requesterId, sessionId)));
    }

    @GetMapping("/sessions/{sessionId}/registrations")
    public ResponseEntity<ApiResponse<SessionRegistrationPageResponse>> listRegistrations(
            Authentication authentication,
            @PathVariable UUID sessionId,
            @RequestParam(name = "status", required = false) RegistrationStatus status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        UUID requesterId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(sessionQueryService.listRegistrations(
                requesterId, sessionId, status == null ? null : status.name(), cursor, limit)));
    }

    private static UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID uuid) {
            return uuid;
        }
        if (principal instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
