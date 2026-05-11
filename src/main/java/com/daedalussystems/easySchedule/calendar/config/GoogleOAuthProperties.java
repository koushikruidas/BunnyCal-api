package com.daedalussystems.easySchedule.calendar.config;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "google.oauth")
@Validated
@Getter
@Setter
public class GoogleOAuthProperties {
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    @NotBlank
    private String frontendBaseUrl;
    @NotBlank
    private String frontendSuccessPath;
    @NotBlank
    private String frontendErrorPath;
    private String frontendSuccessRedirect;
    private String frontendErrorRedirect;
    private List<String> scopes = List.of(
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.readonly");

    public String getFrontendSuccessRedirect() {
        if (hasText(frontendSuccessRedirect)) {
            return frontendSuccessRedirect;
        }
        return join(frontendBaseUrl, frontendSuccessPath);
    }

    public String getFrontendErrorRedirect() {
        if (hasText(frontendErrorRedirect)) {
            return frontendErrorRedirect;
        }
        return join(frontendBaseUrl, frontendErrorPath);
    }
    
    private static String join(String baseUrl, String path) {
        if (!hasText(baseUrl)) {
            throw new IllegalStateException("google.oauth.frontend-base-url must not be empty");
        }
        if (!hasText(path) || !path.startsWith("/")) {
            throw new IllegalStateException("frontend redirect path must start with '/'");
        }
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        return baseUrl + path;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
