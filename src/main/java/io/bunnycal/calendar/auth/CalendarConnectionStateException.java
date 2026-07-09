package io.bunnycal.calendar.auth;

public class CalendarConnectionStateException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;

    public CalendarConnectionStateException(String errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
