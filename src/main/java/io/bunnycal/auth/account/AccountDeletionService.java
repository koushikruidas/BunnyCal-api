package io.bunnycal.auth.account;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.service.RefreshTokenService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final AccountDeletionJobRepository accountDeletionJobRepository;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public Map<String, Object> requestDeletion(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(() ->
                new CustomException(ErrorCode.UNAUTHORIZED, "Session references a deleted account. Please sign in again."));

        AccountDeletionJob existing = accountDeletionJobRepository.findByUserId(userId).orElse(null);
        if (existing != null && EnumSet.of(AccountDeletionJobStatus.QUEUED, AccountDeletionJobStatus.RUNNING).contains(existing.getStatus())) {
            if (user.getDeletionRequestedAt() == null) {
                user.setDeletionRequestedAt(Instant.now());
                userRepository.save(user);
            }
            refreshTokenService.deleteByUserId(userId);
            log.info("ACCOUNT_DELETION_REQUESTED userId={} existingJob=true jobId={}", userId, existing.getId());
            return Map.of("accepted", true, "existingJob", true);
        }

        Instant now = Instant.now();
        user.setDeletionRequestedAt(now);
        userRepository.save(user);
        refreshTokenService.deleteByUserId(userId);

        AccountDeletionJob job;
        if (existing == null) {
            job = AccountDeletionJob.builder()
                    .userId(userId)
                    .status(AccountDeletionJobStatus.QUEUED)
                    .attemptCount(0)
                    .nextAttemptAt(now)
                    .build();
        } else {
            job = existing;
            job.setStatus(AccountDeletionJobStatus.QUEUED);
            job.setNextAttemptAt(now);
            job.setStartedAt(null);
            job.setCompletedAt(null);
            job.setLastErrorCode(null);
            job.setLastErrorMessage(null);
        }

        accountDeletionJobRepository.save(job);
        log.info("ACCOUNT_DELETION_REQUESTED userId={} existingJob={} jobId={}", userId, existing != null, job.getId());
        return Map.of("accepted", true, "existingJob", existing != null);
    }
}
