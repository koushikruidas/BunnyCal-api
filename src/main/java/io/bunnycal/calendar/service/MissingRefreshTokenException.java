package io.bunnycal.calendar.service;

/**
 * The provider returned no refresh token and none is stored for that external account, so the
 * connection cannot be kept alive offline. The user has to re-consent.
 *
 * <p>A distinct type so callers can tell this recoverable case apart from a genuine fault without
 * matching on exception messages. Extends {@link IllegalArgumentException} to preserve the
 * behaviour of existing callers that catch it.
 */
public class MissingRefreshTokenException extends IllegalArgumentException {
    public MissingRefreshTokenException(String message) {
        super(message);
    }
}
