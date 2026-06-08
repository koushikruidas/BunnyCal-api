package io.bunnycal.availability.controller;

import io.bunnycal.availability.dto.ReservationWindowRequest;
import io.bunnycal.availability.dto.ReservationWindowResponse;
import io.bunnycal.availability.service.GroupEventReservationWindowService;
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

@RestController
@RequestMapping("/api/event-types/{eventTypeId}/reservation-windows")
public class GroupEventReservationWindowController {

    private final GroupEventReservationWindowService reservationWindowService;

    public GroupEventReservationWindowController(GroupEventReservationWindowService reservationWindowService) {
        this.reservationWindowService = reservationWindowService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ReservationWindowResponse>>> list(
            Authentication authentication,
            @PathVariable UUID eventTypeId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                reservationWindowService.list(userId, eventTypeId)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<List<ReservationWindowResponse>>> replace(
            Authentication authentication,
            @PathVariable UUID eventTypeId,
            @RequestBody List<ReservationWindowRequest> windows) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                reservationWindowService.replaceWindows(userId, eventTypeId, windows)));
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
