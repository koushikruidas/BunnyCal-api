package com.daedalussystems.easySchedule.calendar.client;

public class CalendarClientException extends RuntimeException {
    private final int statusCode;

    public CalendarClientException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isUnauthorized() {
        return statusCode == 401;
    }

    public boolean isRetryable() {
        return statusCode == 429 || statusCode >= 500;
    }
}
