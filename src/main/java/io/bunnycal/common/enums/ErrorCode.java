package io.bunnycal.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", "An unexpected internal server error occurred."),
    VALIDATION_ERROR("VALIDATION_ERROR", "Request validation failed."),
    UNAUTHORIZED("UNAUTHORIZED", "Authentication is required."),
    FORBIDDEN("FORBIDDEN", "You do not have permission to access this resource."),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "Requested resource was not found."),
    OAUTH_EMAIL_MISSING("OAUTH_EMAIL_MISSING", "OAuth provider did not return an email."),
    INVALID_TIMEZONE("INVALID_TIMEZONE", "Timezone is invalid."),
    TOKEN_EXPIRED("TOKEN_EXPIRED", "Token has expired."),
    TOKEN_INVALID("TOKEN_INVALID", "Token is invalid."),
    OAUTH_INVALID_RESPONSE("OAUTH_INVALID_RESPONSE", "Oauth response is invalid"),
    IDEMPOTENCY_KEY_REQUIRED("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required."),
    IDEMPOTENCY_HASH_MISMATCH("IDEMPOTENCY_HASH_MISMATCH",
            "Idempotency-Key was previously used with a different request payload."),
    IDEMPOTENCY_IN_PROGRESS("IDEMPOTENCY_IN_PROGRESS",
            "A request with this Idempotency-Key is still being processed. Retry shortly."),
    IDEMPOTENCY_RACE("IDEMPOTENCY_RACE",
            "Idempotency-Key was reaped during processing. Retry with a new key."),
    IDEMPOTENCY_RESPONSE_TOO_LARGE("IDEMPOTENCY_RESPONSE_TOO_LARGE",
            "Response too large to cache for idempotency replay."),
    SLOT_ALREADY_BOOKED("SLOT_ALREADY_BOOKED",
            "Requested time overlaps an existing booking for this host."),
    SLOT_UNAVAILABLE("SLOT_UNAVAILABLE",
            "This time slot is no longer available."),
    GOOGLE_EVENT_CREATION_FAILED("GOOGLE_EVENT_CREATION_FAILED",
            "Unable to create Google Calendar event."),
    CALENDAR_SYNC_IN_PROGRESS("CALENDAR_SYNC_IN_PROGRESS",
            "Calendar synchronization is still in progress."),
    TOO_MANY_PENDING_BOOKINGS("TOO_MANY_PENDING_BOOKINGS",
            "Too many pending bookings overlap this time window. Try again later."),
    INVALID_STATE_TRANSITION("INVALID_STATE_TRANSITION",
            "Booking is not in the expected state or version — concurrent update may have occurred."),
    CONFERENCING_DISCONNECT_NOT_SUPPORTED("CONFERENCING_DISCONNECT_NOT_SUPPORTED",
            "This conferencing provider cannot be disconnected on its own.");

    private final String code;
    private final String message;
}
