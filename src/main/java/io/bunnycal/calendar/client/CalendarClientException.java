package io.bunnycal.calendar.client;

public class CalendarClientException extends RuntimeException {
    private final int statusCode;
    private final OAuthError oauthError;

    public CalendarClientException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.oauthError = null;
    }

    public CalendarClientException(int statusCode, String message, OAuthError oauthError) {
        super(message);
        this.statusCode = statusCode;
        this.oauthError = oauthError;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public OAuthError getOAuthError() {
        return oauthError;
    }

    public boolean isUnauthorized() {
        return statusCode == 401;
    }

    public boolean isRetryable() {
        return statusCode == 429 || statusCode >= 500;
    }
}
