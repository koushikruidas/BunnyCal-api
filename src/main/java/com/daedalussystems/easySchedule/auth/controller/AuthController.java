package com.daedalussystems.easySchedule.auth.controller;

import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.auth.security.jwt.JwtTokenProvider;
import com.daedalussystems.easySchedule.auth.domain.token.RefreshToken;
import com.daedalussystems.easySchedule.auth.dto.AuthResponse;
import com.daedalussystems.easySchedule.auth.dto.LogoutRequest;
import com.daedalussystems.easySchedule.auth.dto.RefreshRequest;
import com.daedalussystems.easySchedule.auth.dto.UserDto;
import com.daedalussystems.easySchedule.auth.service.RefreshTokenService;
import com.daedalussystems.easySchedule.auth.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@RequestBody RefreshRequest request) {
        RefreshToken refreshToken = refreshTokenService.validateRefreshToken(request.getRefreshToken());
        User user = refreshToken.getUser();

        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String rotatedRefreshToken = refreshTokenService.rotateRefreshToken(refreshToken);

        AuthResponse response = AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rotatedRefreshToken)
                .user(UserDto.from(user))
                .build();

        return ApiResponse.success(response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody LogoutRequest request) {
        refreshTokenService.deleteByUserId(request.getUserId());
        return ApiResponse.success(null);
    }
}
