package io.bunnycal.auth.account;

import io.bunnycal.auth.service.RefreshTokenService;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.service.BookingService;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.service.SessionService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionWorker {

    private final AccountDeletionJobRepository accountDeletionJobRepository;
    private final AccountDeletionTransactions tx;
    private final RefreshTokenService refreshTokenService;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final EventSessionRepository eventSessionRepository;
    private final SessionService sessionService;
    private final List<AccountDeletionProviderCleanupStrategy> cleanupStrategies;

    @Value("${account.deletion.worker.batch-size:10}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${account.deletion.worker.fixed-delay-ms:5000}")
    public void run() {
        Instant now = Instant.now();
        List<AccountDeletionJob> jobs = accountDeletionJobRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                EnumSet.of(AccountDeletionJobStatus.QUEUED, AccountDeletionJobStatus.FAILED),
                now,
                PageRequest.of(0, batchSize));
        for (AccountDeletionJob job : jobs) {
            process(job.getId());
        }
    }

    /**
     * Orchestration only — every database write goes through {@link AccountDeletionTransactions},
     * whose methods each run in their own transaction. See that class for why the phases are
     * separate beans and separate transactions rather than one enclosing transaction here.
     */
    protected void process(UUID jobId) {
        AccountDeletionJob job = tx.markRunning(jobId);
        if (job == null) {
            return;
        }
        log.info("ACCOUNT_DELETION_STARTED userId={} jobId={} attempt={}", job.getUserId(), job.getId(), job.getAttemptCount());

        try {
            executeDeletion(job.getUserId());
            tx.markCompleted(jobId);
            log.info("ACCOUNT_DELETION_COMPLETED userId={} jobId={}", job.getUserId(), job.getId());
        } catch (RuntimeException ex) {
            log.warn("ACCOUNT_DELETION_FAILED userId={} jobId={} message={}", job.getUserId(), job.getId(), ex.getMessage(), ex);
            tx.markFailed(job.getId(), job.getAttemptCount(), ex);
        }
    }

    private void executeDeletion(UUID userId) {
        refreshTokenService.deleteByUserId(userId);
        cancelFutureBookings(userId);
        cancelFutureSessions(userId);
        cleanupProviders(userId);
        tx.deleteProviderRecords(userId);
        tx.deleteUserOwnedObjects(userId);
        tx.finalizeDeletion(userId);
    }

    private void cancelFutureBookings(UUID userId) {
        for (BookingRepository.BookingStateRow row : bookingRepository.findFutureActiveStatesByHostId(userId, Instant.now())) {
            bookingService.cancelBooking(
                    row.getId(),
                    row.getHostId(),
                    row.getVersion(),
                    io.bunnycal.booking.service.CancellationSource.HOST,
                    "account_deleted");
        }
    }

    private void cancelFutureSessions(UUID userId) {
        for (EventSession session : eventSessionRepository.findFutureActiveByHostId(userId, Instant.now())) {
            sessionService.cancelSession(session.getId(), userId);
        }
    }

    /** A provider that cannot be cleaned up (revoked token, API down) must not block the deletion. */
    private void cleanupProviders(UUID userId) {
        for (AccountDeletionProviderCleanupStrategy strategy : cleanupStrategies) {
            try {
                strategy.cleanup(userId);
            } catch (RuntimeException ex) {
                log.warn("account_deletion_provider_cleanup_failed userId={} provider={} message={}",
                        userId, strategy.providerKey(), ex.getMessage(), ex);
            }
        }
    }
}
