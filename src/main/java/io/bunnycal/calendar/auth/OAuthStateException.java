package io.bunnycal.calendar.auth;

public class OAuthStateException extends RuntimeException {
    public enum Reason {
        INVALID,
        EXPIRED,
        MISSING_USER
    }

    private final Reason reason;

    public OAuthStateException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public OAuthStateException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
