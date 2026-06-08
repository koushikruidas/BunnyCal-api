package io.bunnycal.availability.controller;

import io.bunnycal.availability.dto.EventAvailabilityWindowRequest;
import io.bunnycal.availability.dto.EventAvailabilityWindowResponse;
import io.bunnycal.availability.service.EventAvailabilityWindowService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-event availability FILTER windows for demand-driven event types
 * (ONE_ON_ONE, ROUND_ROBIN, COLLECTIVE).
 *
 * Separate from {@code /api/availability/rules/bulk} (host-global working hours) and
 * from {@code /api/event-types/{id}/reservation-windows} (GROUP ownership). Event
 * creation/edit flows write here for demand-driven types and MUST NOT mutate host
 * availability.
 */
@RestController
@RequestMapping("/api/event-types/{eventTypeId}/availability-windows")
public class EventAvailabilityWindowController {

    private final EventAvailabilityWindowService availabilityWindowService;

    public EventAvailabilityWindowController(EventAvailabilityWindowService availabilityWindowService) {
        this.availabilityWindowService = availabilityWindowService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EventAvailabilityWindowResponse>>> list(
            Authentication authentication,
            @PathVariable UUID eventTypeId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                availabilityWindowService.list(userId, eventTypeId)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<List<EventAvailabilityWindowResponse>>> replace(
            Authentication authentication,
            @PathVariable UUID eventTypeId,
            @RequestBody List<EventAvailabilityWindowRequest> windows) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                availabilityWindowService.replaceWindows(userId, eventTypeId, windows)));
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
