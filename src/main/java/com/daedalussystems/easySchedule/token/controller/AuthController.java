package com.daedalussystems.easySchedule.token.controller;

import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.security.jwt.JwtTokenProvider;
import com.daedalussystems.easySchedule.token.domain.RefreshToken;
import com.daedalussystems.easySchedule.token.dto.AuthResponse;
import com.daedalussystems.easySchedule.token.dto.LogoutRequest;
import com.daedalussystems.easySchedule.token.dto.RefreshRequest;
import com.daedalussystems.easySchedule.token.dto.UserDto;
import com.daedalussystems.easySchedule.token.service.RefreshTokenService;
import com.daedalussystems.easySchedule.user.domain.User;
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
        String rotatedRefreshToken = refreshTokenService.rotateRefreshToken(user, request.getRefreshToken());

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
