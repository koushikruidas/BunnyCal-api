package io.bunnycal.availability.controller;

import io.bunnycal.availability.dto.AvailabilityOverrideCreateRequest;
import io.bunnycal.availability.dto.AvailabilityOverrideResponse;
import io.bunnycal.availability.dto.AvailabilityRuleResponse;
import io.bunnycal.availability.dto.BulkAvailabilityRulesUpsertRequest;
import io.bunnycal.availability.dto.GroupReservationBlockerResponse;
import io.bunnycal.availability.service.AvailabilityService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/rules")
    public ResponseEntity<ApiResponse<List<AvailabilityRuleResponse>>> getRules(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(availabilityService.getRules(userId)));
    }

    @PutMapping("/rules/bulk")
    public ResponseEntity<ApiResponse<List<AvailabilityRuleResponse>>> upsertRules(
            Authentication authentication,
            @RequestBody BulkAvailabilityRulesUpsertRequest request) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(availabilityService.replaceRules(userId, request)));
    }

    @PostMapping("/overrides")
    public ResponseEntity<ApiResponse<AvailabilityOverrideResponse>> createOverride(
            Authentication authentication,
            @RequestBody AvailabilityOverrideCreateRequest request) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(availabilityService.createOverride(userId, request)));
    }

    @GetMapping("/overrides")
    public ResponseEntity<ApiResponse<List<AvailabilityOverrideResponse>>> getOverrides(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(availabilityService.getOverrides(userId, from, to)));
    }

    @GetMapping("/reservation-blockers")
    public ResponseEntity<ApiResponse<List<GroupReservationBlockerResponse>>> getReservationBlockers(
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(availabilityService.getReservationBlockers(userId)));
    }

    @DeleteMapping("/overrides/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteOverride(Authentication authentication, @PathVariable("id") UUID id) {
        UUID userId = extractUserId(authentication);
        availabilityService.deleteOverride(userId, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID) {
            return (UUID) principal;
        }
        if (principal instanceof String principalString) {
            try {
                return UUID.fromString(principalString);
            } catch (IllegalArgumentException ex) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }

        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
