package com.daedalussystems.easySchedule.common.enums;

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
            "Response too large to cache for idempotency replay.");

    private final String code;
    private final String message;
}
