package io.bunnycal.auth.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Single resolution point for converting an authenticated userId (from JWT) into a User entity.
 * Returns HTTP 401 — not 404 — when the backing user record is missing, because a valid JWT that
 * references a non-existent user means the session is stale (e.g. database was reset). Callers
 * should never receive a null user; they either get a User or an exception.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionUserResolver {

    private final UserRepository userRepository;

    /**
     * Resolves the authenticated user, throwing 401 if the record no longer exists.
     *
     * @param userId   the UUID extracted from the JWT principal
     * @param endpoint a short identifier for logging (e.g. "GET:/api/event-types")
     * @return the User entity, never null
     */
    public User require(UUID userId, String endpoint) {
        return userRepository.findById(userId).orElseThrow(() -> {
            log.warn("session_user_not_found userId={} endpoint={} reason=user_not_found", userId, endpoint);
            return new CustomException(ErrorCode.UNAUTHORIZED,
                    "Session references a deleted account. Please sign in again.");
        });
    }
}
