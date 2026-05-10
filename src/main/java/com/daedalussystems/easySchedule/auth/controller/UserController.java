package com.daedalussystems.easySchedule.auth.controller;

import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.dto.TimezoneUpdateRequest;
import com.daedalussystems.easySchedule.auth.dto.UserDto;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.auth.service.TimeZoneService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final TimeZoneService timeZoneService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserDto>> getCurrentUser(
            Authentication authentication,
            @RequestHeader(value = "X-Timezone", required = false) String timezoneHeader) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        UUID userId = extractUserId(authentication.getPrincipal());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
        if (timezoneHeader != null && !timezoneHeader.isBlank()) {
            timeZoneService.applyTimezoneUpdate(user, timezoneHeader);
            user = userRepository.save(user);
        }

        return ResponseEntity.ok(ApiResponse.success(UserDto.from(user)));
    }

    @PutMapping("/me/timezone")
    public ResponseEntity<ApiResponse<UserDto>> updateTimezone(Authentication authentication,
                                                               @RequestBody TimezoneUpdateRequest request) {
        if (request == null || request.timezone() == null || request.timezone().isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "timezone is required.");
        }
        UUID userId = extractUserId(authentication == null ? null : authentication.getPrincipal());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
        timeZoneService.applyTimezoneUpdate(user, request.timezone());
        User saved = userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success(UserDto.from(saved)));
    }

    private UUID extractUserId(Object principal) {
        if (principal instanceof UUID) {
            return (UUID) principal;
        }
        if (principal instanceof String) {
            try {
                return UUID.fromString((String) principal);
            } catch (IllegalArgumentException ex) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
