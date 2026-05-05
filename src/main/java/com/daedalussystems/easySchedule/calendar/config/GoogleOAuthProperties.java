package com.daedalussystems.easySchedule.calendar.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google.oauth")
public class GoogleOAuthProperties {
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String frontendSuccessRedirect = "http://localhost:3000/calendar-connected";
    private String frontendErrorRedirect = "http://localhost:3000/calendar-error";
    private List<String> scopes = List.of(
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.readonly");

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public String getFrontendSuccessRedirect() {
        return frontendSuccessRedirect;
    }

    public void setFrontendSuccessRedirect(String frontendSuccessRedirect) {
        this.frontendSuccessRedirect = frontendSuccessRedirect;
    }

    public String getFrontendErrorRedirect() {
        return frontendErrorRedirect;
    }

    public void setFrontendErrorRedirect(String frontendErrorRedirect) {
        this.frontendErrorRedirect = frontendErrorRedirect;
    }
}
