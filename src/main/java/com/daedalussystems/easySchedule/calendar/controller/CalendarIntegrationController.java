package com.daedalussystems.easySchedule.calendar.controller;

import com.daedalussystems.easySchedule.calendar.service.CalendarOAuthService;
import com.daedalussystems.easySchedule.calendar.config.GoogleOAuthProperties;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integrations/calendar")
public class CalendarIntegrationController {
    private static final Logger log = LoggerFactory.getLogger(CalendarIntegrationController.class);
    private static final String GOOGLE_PROVIDER = "google";
    private final CalendarOAuthService oauthService;
    private final GoogleOAuthProperties googleOAuthProperties;

    public CalendarIntegrationController(CalendarOAuthService oauthService, GoogleOAuthProperties googleOAuthProperties) {
        this.oauthService = oauthService;
        this.googleOAuthProperties = googleOAuthProperties;
    }

    @GetMapping("/google/connect")
    public ResponseEntity<ApiResponse<Map<String, String>>> connectGoogle(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        String redirectUrl = oauthService.buildGoogleConnectUrl(userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("redirectUrl", redirectUrl)));
    }

    @GetMapping("/google/callback")
    public ResponseEntity<Void> callbackGoogle(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {

        log.info("OAuth callback received: codePresent={}, statePresent={}",
                code != null && !code.isBlank(),
                state != null && !state.isBlank());

        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            log.warn("OAuth callback validation failed: missing code/state");
            return ResponseEntity.status(302)
                    .location(errorRedirect("VALIDATION_ERROR"))
                    .build();
        }

        try {
            log.info("Processing Google OAuth callback...");

            oauthService.handleGoogleCallback(code, state);

            log.info("OAuth callback SUCCESS");

            return ResponseEntity.status(302)
                    .location(URI.create(googleOAuthProperties.getFrontendSuccessRedirect()))
                    .build();

        } catch (RuntimeException ex) {
            String errorCode = mapErrorCode(ex);

            // 🔥 THIS is the critical part
            log.error("OAuth callback FAILED: errorCode={}, message={}",
                    errorCode,
                    ex.getMessage(),
                    ex); // <-- prints full stacktrace

            return ResponseEntity.status(302)
                    .location(errorRedirect(errorCode))
                    .build();
        }
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, String>>> status(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(Map.of("google", oauthService.googleConnectionStatus(userId))));
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<ApiResponse<Void>> disconnect(@PathVariable("provider") String provider,
                                                        Authentication authentication) {
        UUID userId = extractUserId(authentication);
        if (!GOOGLE_PROVIDER.equalsIgnoreCase(provider)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, "Unsupported provider"));
        }
        oauthService.disconnectGoogle(userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID userId)) {
            throw new IllegalStateException("Authenticated user id is required");
        }
        return userId;
    }

    private URI errorRedirect(String code) {
        String sep = googleOAuthProperties.getFrontendErrorRedirect().contains("?") ? "&" : "?";
        return URI.create(googleOAuthProperties.getFrontendErrorRedirect() + sep + "code=" + code);
    }

    private static String mapErrorCode(RuntimeException ex) {
        if (ex instanceof IllegalArgumentException) {
            return "OAUTH_INVALID_RESPONSE";
        }
        return "INTERNAL_SERVER_ERROR";
    }
}
