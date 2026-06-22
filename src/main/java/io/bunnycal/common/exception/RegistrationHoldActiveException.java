package io.bunnycal.common.exception;

import io.bunnycal.common.enums.ErrorCode;
import java.time.Instant;
import lombok.Getter;

/**
 * Thrown when a guest attempts to register for a session that already has an
 * active PENDING hold for the same email address.
 *
 * <p>Carries the hold's {@code expiresAt} timestamp so the response body can
 * include it without hard-coding any hold-duration constant.
 */
@Getter
public class RegistrationHoldActiveException extends CustomException {

    private final Instant expiresAt;

    public RegistrationHoldActiveException(Instant expiresAt) {
        super(ErrorCode.REGISTRATION_HOLD_ACTIVE);
        this.expiresAt = expiresAt;
    }
}
