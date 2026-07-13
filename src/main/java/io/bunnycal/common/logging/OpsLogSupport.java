package io.bunnycal.common.logging;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;

public final class OpsLogSupport {

    private OpsLogSupport() {
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static String bookingReasonCode(Throwable throwable) {
        if (throwable instanceof CustomException customException) {
            return bookingReasonCode(customException.getErrorCode());
        }
        return "INTERNAL_ERROR";
    }

    public static String bookingReasonCode(ErrorCode errorCode) {
        if (errorCode == null) {
            return "INTERNAL_ERROR";
        }
        return switch (errorCode) {
            case SLOT_UNAVAILABLE, SLOT_ALREADY_BOOKED -> "HOST_UNAVAILABLE";
            case REGISTRATION_HOLD_ACTIVE -> "GROUP_STALE_HOLD";
            case REGISTRATION_EXPIRED -> "GROUP_HOLD_EXPIRED";
            case SESSION_CAPACITY_FULL -> "SESSION_FULL";
            case SESSION_CANCELLED -> "SESSION_CLOSED";
            case EVENT_TYPE_NOT_PUBLISHED -> "SESSION_NOT_BOOKABLE";
            case VALIDATION_ERROR -> "VALIDATION_FAILED";
            case INTERNAL_SERVER_ERROR -> "BOOKING_PERSIST_FAILED";
            default -> errorCode.getCode();
        };
    }

    public static String notificationReasonCode(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        return switch (reason) {
            case "MISSING_OR_INVALID", "UNDELIVERABLE" -> "NO_RECIPIENT";
            case "duplicate" -> "DUPLICATE_SUPPRESSED";
            case "missing_event_id" -> "TEMPLATE_RESOLUTION_FAILED";
            default -> reason;
        };
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
