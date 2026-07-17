package io.bunnycal.availability.controller;

import io.bunnycal.availability.dto.EventAvailabilityScheduleRequest;
import io.bunnycal.availability.dto.EventAvailabilityScheduleResponse;
import io.bunnycal.availability.service.EventAvailabilityWindowService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Atomic schedule-mode API for ONE_ON_ONE, ROUND_ROBIN, and COLLECTIVE event types. */
@RestController
@RequestMapping("/api/event-types/{eventTypeId}/availability-schedule")
public class EventAvailabilityScheduleController {

    private final EventAvailabilityWindowService availabilityWindowService;

    public EventAvailabilityScheduleController(EventAvailabilityWindowService availabilityWindowService) {
        this.availabilityWindowService = availabilityWindowService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<EventAvailabilityScheduleResponse>> get(
            Authentication authentication,
            @PathVariable UUID eventTypeId) {
        return ResponseEntity.ok(ApiResponse.success(
                availabilityWindowService.getSchedule(extractUserId(authentication), eventTypeId)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<EventAvailabilityScheduleResponse>> replace(
            Authentication authentication,
            @PathVariable UUID eventTypeId,
            @RequestBody EventAvailabilityScheduleRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                availabilityWindowService.replaceSchedule(extractUserId(authentication), eventTypeId, request)));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID uuid) return uuid;
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
