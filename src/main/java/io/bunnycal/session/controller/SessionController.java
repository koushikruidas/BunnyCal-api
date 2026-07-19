package io.bunnycal.session.controller;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.session.domain.RegistrationStatus;
import io.bunnycal.session.domain.SessionStatus;
import io.bunnycal.session.dto.PinnedSessionResponse;
import io.bunnycal.session.dto.RescheduleConflictResponse;
import io.bunnycal.session.dto.SeriesCancelPreviewResponse;
import io.bunnycal.session.dto.SeriesOperationResponse;
import io.bunnycal.session.dto.SessionDetailResponse;
import io.bunnycal.session.dto.SessionPageResponse;
import io.bunnycal.session.dto.SessionRegistrationPageResponse;
import io.bunnycal.session.dto.SessionRescheduleRequest;
import io.bunnycal.session.service.RescheduleConflictService;
import io.bunnycal.session.service.RescheduleConflicts;
import io.bunnycal.session.service.SessionService;
import io.bunnycal.session.service.SessionQueryService;
import io.bunnycal.session.service.SessionSeriesService;
import io.bunnycal.sync.state.SyncJobStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final SessionSeriesService sessionSeriesService;
    private final RescheduleConflictService rescheduleConflictService;

    public SessionController(SessionQueryService sessionQueryService,
                             SessionService sessionService,
                             SessionSeriesService sessionSeriesService,
                             RescheduleConflictService rescheduleConflictService) {
        this.sessionQueryService = sessionQueryService;
        this.sessionService = sessionService;
        this.sessionSeriesService = sessionSeriesService;
        this.rescheduleConflictService = rescheduleConflictService;
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

    /** Booked sessions that stayed behind when the recurrence rule changed. */
    @GetMapping("/event-types/{eventTypeId}/sessions/pinned")
    public ResponseEntity<ApiResponse<List<PinnedSessionResponse>>> listPinnedSessions(
            Authentication authentication,
            @PathVariable UUID eventTypeId) {
        UUID requesterId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                sessionSeriesService.listPinnedSessions(requesterId, eventTypeId)));
    }

    /**
     * Booked future sessions per reservation window, keyed by window id. Lets the windows
     * editor warn before an edit that would pin sessions, rather than after.
     */
    @GetMapping("/event-types/{eventTypeId}/sessions/booked-counts")
    public ResponseEntity<ApiResponse<Map<UUID, Long>>> countBookedSessionsByWindow(
            Authentication authentication,
            @PathVariable UUID eventTypeId) {
        UUID requesterId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                sessionSeriesService.countBookedSessionsByWindow(requesterId, eventTypeId)));
    }

    /**
     * Queues pinned sessions to move onto the schedule their rule now describes.
     *
     * <p>Returns as soon as the work is queued: a long-running class can have hundreds
     * of booked sessions, and each move is a calendar write plus a notification batch.
     */
    @PostMapping("/event-types/{eventTypeId}/sessions/move-pinned")
    public ResponseEntity<ApiResponse<SeriesOperationResponse>> movePinnedSessions(
            Authentication authentication,
            @PathVariable UUID eventTypeId,
            @RequestBody(required = false) MovePinnedSessionsRequest request) {
        UUID requesterId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(sessionSeriesService.movePinnedSessions(
                requesterId, eventTypeId,
                request != null ? request.sessionIds() : null,
                request != null ? request.startTime() : null)));
    }

    /** What a series cancel would affect — call before {@link #cancelSeries}. */
    @GetMapping("/event-types/{eventTypeId}/sessions/cancel-series/preview")
    public ResponseEntity<ApiResponse<SeriesCancelPreviewResponse>> previewCancelSeries(
            Authentication authentication,
            @PathVariable UUID eventTypeId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from) {
        UUID requesterId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                sessionSeriesService.previewCancelSeries(requesterId, eventTypeId, from)));
    }

    /**
     * Cancels every upcoming session for this event type. Destructive and wide-reaching;
     * clients should confirm against the preview first.
     */
    @PostMapping("/event-types/{eventTypeId}/sessions/cancel-series")
    public ResponseEntity<ApiResponse<SeriesOperationResponse>> cancelSeries(
            Authentication authentication,
            @PathVariable UUID eventTypeId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from) {
        UUID requesterId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                sessionSeriesService.cancelSeries(requesterId, eventTypeId, from)));
    }

    /** Body for the bulk move; both fields optional (all pinned, rule-derived times). */
    public record MovePinnedSessionsRequest(List<UUID> sessionIds, Instant startTime) {}

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
            @RequestBody SessionRescheduleRequest request) {
        if (request == null || request.startTime() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime is required.");
        }
        UUID requesterId = extractUserId(authentication);
        sessionQueryService.getSessionDetail(requesterId, sessionId);
        sessionService.rescheduleSession(sessionId, requesterId, request.startTime(),
                request.acknowledgeExternalConflicts());
        return ResponseEntity.ok(ApiResponse.success(sessionQueryService.getSessionDetail(requesterId, sessionId)));
    }

    /**
     * Advisory preview of what a proposed time would collide with. Drives the reschedule dialog.
     * The reschedule endpoint re-checks independently — a clean preview is never sufficient.
     */
    @GetMapping("/sessions/{sessionId}/reschedule-conflicts")
    public ResponseEntity<ApiResponse<RescheduleConflictResponse>> previewRescheduleConflicts(
            Authentication authentication,
            @PathVariable UUID sessionId,
            @RequestParam Instant startTime) {
        UUID requesterId = extractUserId(authentication);
        SessionDetailResponse detail = sessionQueryService.getSessionDetail(requesterId, sessionId);

        Duration duration = Duration.between(detail.startTime(), detail.endTime());
        RescheduleConflicts conflicts = rescheduleConflictService.check(
                requesterId, startTime, startTime.plus(duration), sessionId);

        return ResponseEntity.ok(ApiResponse.success(new RescheduleConflictResponse(
                conflicts.hasHard(),
                !conflicts.hasHard() && conflicts.hasSoft(),
                conflicts.hard().stream().map(SessionController::toItem).toList(),
                conflicts.soft().stream().map(SessionController::toItem).toList())));
    }

    private static RescheduleConflictResponse.ConflictItem toItem(RescheduleConflicts.Conflict c) {
        return new RescheduleConflictResponse.ConflictItem(
                c.title(), c.startTime(), c.endTime(), c.source());
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
