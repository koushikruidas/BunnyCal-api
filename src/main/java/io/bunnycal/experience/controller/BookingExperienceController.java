package io.bunnycal.experience.controller;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.experience.dto.BookingExperienceRequest;
import io.bunnycal.experience.dto.BookingExperienceResponse;
import io.bunnycal.experience.dto.CreateExperienceRequest;
import io.bunnycal.experience.service.BookingExperienceService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/experiences")
public class BookingExperienceController {

    private final BookingExperienceService experienceService;

    public BookingExperienceController(BookingExperienceService experienceService) {
        this.experienceService = experienceService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BookingExperienceResponse>> create(
            Authentication auth, @RequestBody CreateExperienceRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                experienceService.createExperience(userId(auth), request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BookingExperienceResponse>>> list(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(experienceService.getExperiences(userId(auth))));
    }

    @GetMapping("/{experienceId}")
    public ResponseEntity<ApiResponse<BookingExperienceResponse>> get(
            Authentication auth, @PathVariable UUID experienceId) {
        return ResponseEntity.ok(ApiResponse.success(
                experienceService.getExperience(userId(auth), experienceId)));
    }

    @PutMapping("/{experienceId}")
    public ResponseEntity<ApiResponse<BookingExperienceResponse>> update(
            Authentication auth, @PathVariable UUID experienceId,
            @RequestBody BookingExperienceRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                experienceService.updateExperience(userId(auth), experienceId, request)));
    }

    @PostMapping("/{experienceId}/activate")
    public ResponseEntity<ApiResponse<BookingExperienceResponse>> activate(
            Authentication auth, @PathVariable UUID experienceId) {
        return ResponseEntity.ok(ApiResponse.success(
                experienceService.activateExperience(userId(auth), experienceId)));
    }

    @PostMapping("/{experienceId}/archive")
    public ResponseEntity<ApiResponse<BookingExperienceResponse>> archive(
            Authentication auth, @PathVariable UUID experienceId) {
        return ResponseEntity.ok(ApiResponse.success(
                experienceService.archiveExperience(userId(auth), experienceId)));
    }

    @DeleteMapping("/{experienceId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            Authentication auth, @PathVariable UUID experienceId) {
        experienceService.deleteExperience(userId(auth), experienceId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/{experienceId}/embed-snippet")
    public ResponseEntity<ApiResponse<String>> embedSnippet(
            Authentication auth, @PathVariable UUID experienceId) {
        return ResponseEntity.ok(ApiResponse.success(
                experienceService.getEmbedSnippet(userId(auth), experienceId)));
    }

    private UUID userId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) throw new CustomException(ErrorCode.UNAUTHORIZED);
        Object p = auth.getPrincipal();
        if (p instanceof UUID uuid) return uuid;
        if (p instanceof String s) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException ex) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
