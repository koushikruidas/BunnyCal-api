package io.bunnycal.auth.account;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AccountAccessGuard {

    public boolean isDeletionBlocked(User user) {
        return user != null && user.getDeletionRequestedAt() != null;
    }

    public void requireAccessible(User user, UUID userId, String endpoint) {
        if (!isDeletionBlocked(user)) {
            return;
        }
        log.warn("account_access_blocked userId={} endpoint={} deletionRequestedAt={}",
                userId, endpoint, user.getDeletionRequestedAt());
        throw new CustomException(ErrorCode.UNAUTHORIZED,
                "Account deletion is in progress. Please sign in again later.");
    }
}
