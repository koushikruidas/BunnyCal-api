package io.bunnycal.common.exception;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.session.dto.HoldActiveResponse;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final TimeSource timeSource;

    public GlobalExceptionHandler(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    @ExceptionHandler(RegistrationHoldActiveException.class)
    public ResponseEntity<ApiResponse<HoldActiveResponse>> handleRegistrationHoldActive(
            RegistrationHoldActiveException ex) {
        HoldActiveResponse data = HoldActiveResponse.of(ex.getExpiresAt(), timeSource.now());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.<HoldActiveResponse>builder()
                        .success(false)
                        .data(data)
                        .error(ApiResponse.ErrorResponse.builder()
                                .code(ex.getErrorCode().getCode())
                                .message(ex.getMessage())
                                .build())
                        .build());
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException ex) {
        HttpStatus status = mapStatus(ex.getErrorCode());
        if (ex.getErrorCode() == ErrorCode.IDEMPOTENCY_IN_PROGRESS) {
            return ResponseEntity.status(status)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
        }
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex) {
        String message = Optional.ofNullable(ex.getBindingResult().getFieldError())
                .map(FieldError::getDefaultMessage)
                .orElse(ErrorCode.VALIDATION_ERROR.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        ex.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    private HttpStatus mapStatus(ErrorCode errorCode) {
        switch (errorCode) {
            case IDEMPOTENCY_KEY_REQUIRED:
                return HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_HASH_MISMATCH:
                return HttpStatus.UNPROCESSABLE_ENTITY;
            case IDEMPOTENCY_IN_PROGRESS:
            case SLOT_ALREADY_BOOKED:
            case SLOT_UNAVAILABLE:
            case CALENDAR_SYNC_IN_PROGRESS:
            case REGISTRATION_HOLD_ACTIVE:
                return HttpStatus.CONFLICT;
            case GOOGLE_EVENT_CREATION_FAILED:
                return HttpStatus.BAD_GATEWAY;
            case TOO_MANY_PENDING_BOOKINGS:
                return HttpStatus.TOO_MANY_REQUESTS;
            case IDEMPOTENCY_RACE:
                return HttpStatus.SERVICE_UNAVAILABLE;
            case UNAUTHORIZED:
            case TOKEN_EXPIRED:
            case TOKEN_INVALID:
                return HttpStatus.UNAUTHORIZED;
            case FORBIDDEN:
            case TEAM_OWNER_REQUIRED:
            case TEAM_INVITATION_EMAIL_MISMATCH:
                return HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND:
            case TEAM_INVITATION_INVALID:
                return HttpStatus.NOT_FOUND;
            case TEAM_SLUG_TAKEN:
            case TEAM_MEMBER_ALREADY_EXISTS:
            case TEAM_INVITATION_ALREADY_PENDING:
            case TEAM_LAST_OWNER:
                return HttpStatus.CONFLICT;
            case VALIDATION_ERROR:
            case PARTICIPANTS_REQUIRED:
            case PARTICIPANTS_NOT_ALLOWED_FOR_KIND:
            case PARTICIPANT_NOT_IN_TEAM:
                return HttpStatus.BAD_REQUEST;
            case HOST_NOT_SCHEDULABLE:
                return HttpStatus.GONE;
            case EVENT_TYPE_NOT_PUBLISHED:
            case UNPUBLISHABLE_EVENT_TYPE:
                return HttpStatus.CONFLICT;
            default:
                return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }
}
