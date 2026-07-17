package io.bunnycal.availability.controller;

import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.availability.dto.EventTypeParticipantResponse;
import io.bunnycal.availability.dto.EventTypeParticipantsRequest;
import io.bunnycal.availability.dto.EventTypeSummaryResponse;
import io.bunnycal.availability.dto.PublishReadinessResponse;
import io.bunnycal.availability.dto.RoundRobinStatsResponse;
import io.bunnycal.availability.dto.UpdateEventTypeRequest;
import io.bunnycal.availability.service.EventTypeParticipantService;
import io.bunnycal.availability.service.EventTypeService;
import io.bunnycal.availability.service.RoundRobinStatsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/event-types")
public class EventTypeController {
    private final EventTypeService eventTypeService;
    private final EventTypeParticipantService participantService;
    private final RoundRobinStatsService rrStatsService;

    public EventTypeController(EventTypeService eventTypeService,
                               EventTypeParticipantService participantService,
                               RoundRobinStatsService rrStatsService) {
        this.eventTypeService = eventTypeService;
        this.participantService = participantService;
        this.rrStatsService = rrStatsService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EventTypeSummaryResponse>> create(Authentication authentication,
                                                                        @RequestBody CreateEventTypeRequest request) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(eventTypeService.create(userId, request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EventTypeSummaryResponse>>> list(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(eventTypeService.list(userId)));
    }

    @GetMapping("/{eventTypeId}")
    public ResponseEntity<ApiResponse<EventTypeSummaryResponse>> get(Authentication authentication,
                                                                     @PathVariable UUID eventTypeId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(eventTypeService.get(userId, eventTypeId)));
    }

    @PutMapping("/{eventTypeId}")
    public ResponseEntity<ApiResponse<EventTypeSummaryResponse>> update(Authentication authentication,
                                                                        @PathVariable UUID eventTypeId,
                                                                        @RequestBody UpdateEventTypeRequest request) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(eventTypeService.update(userId, eventTypeId, request)));
    }

    @DeleteMapping("/{eventTypeId}")
    public ResponseEntity<ApiResponse<Void>> delete(Authentication authentication,
                                                    @PathVariable UUID eventTypeId) {
        UUID userId = extractUserId(authentication);
        eventTypeService.delete(userId, eventTypeId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Participants (Phase 2) ──────────────────────────────────────────────────

    @GetMapping("/{eventTypeId}/participants")
    public ResponseEntity<ApiResponse<List<EventTypeParticipantResponse>>> listParticipants(
            Authentication authentication,
            @PathVariable UUID eventTypeId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                participantService.listParticipants(userId, eventTypeId)));
    }

    @GetMapping("/participants/readiness")
    public ResponseEntity<ApiResponse<List<EventTypeParticipantResponse>>> checkReadiness(
            Authentication authentication,
            @RequestParam("userIds") List<UUID> userIds) {
        UUID actingUserId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                participantService.checkReadiness(actingUserId, userIds)));
    }

    @GetMapping("/{eventTypeId}/rr-stats")
    public ResponseEntity<ApiResponse<RoundRobinStatsResponse>> getRrStats(
            Authentication authentication,
            @PathVariable UUID eventTypeId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(rrStatsService.getStats(userId, eventTypeId)));
    }

    @GetMapping("/{eventTypeId}/publish-readiness")
    public ResponseEntity<ApiResponse<PublishReadinessResponse>> publishReadiness(
            Authentication authentication,
            @PathVariable UUID eventTypeId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                participantService.publishReadiness(userId, eventTypeId)));
    }

    @PutMapping("/{eventTypeId}/publish")
    public ResponseEntity<ApiResponse<PublishReadinessResponse>> publish(
            Authentication authentication,
            @PathVariable UUID eventTypeId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(eventTypeService.publish(userId, eventTypeId)));
    }

    @PutMapping("/{eventTypeId}/unpublish")
    public ResponseEntity<ApiResponse<PublishReadinessResponse>> unpublish(
            Authentication authentication,
            @PathVariable UUID eventTypeId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(eventTypeService.unpublish(userId, eventTypeId)));
    }

    @PutMapping("/{eventTypeId}/participants")
    public ResponseEntity<ApiResponse<List<EventTypeParticipantResponse>>> replaceParticipants(
            Authentication authentication,
            @PathVariable UUID eventTypeId,
            @RequestBody EventTypeParticipantsRequest request) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                participantService.replaceParticipants(userId, eventTypeId,
                        request == null ? null : request.userIds())));
    }

    private UUID extractUserId(Authentication authentication) {
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
            } catch (IllegalArgumentException ex) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
