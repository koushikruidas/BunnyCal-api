package io.bunnycal.auth.account;

import io.bunnycal.auth.domain.identity.AuthIdentity;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.service.RefreshTokenService;
import io.bunnycal.availability.repository.AvailabilityOverrideRepository;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.draft.repository.HostDraftRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.service.BookingService;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarConnectionSyncCursorRepository;
import io.bunnycal.conferencing.repository.ZoomConferencingConnectionRepository;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.repository.FormRepository;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.service.SessionService;
import io.bunnycal.team.repository.ParticipantSetupRequestRepository;
import io.bunnycal.team.repository.TeamMemberRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionWorker {

    private final AccountDeletionJobRepository accountDeletionJobRepository;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final EventSessionRepository eventSessionRepository;
    private final SessionService sessionService;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarConnectionCalendarRepository calendarConnectionCalendarRepository;
    private final CalendarConnectionSyncCursorRepository calendarConnectionSyncCursorRepository;
    private final ZoomConferencingConnectionRepository zoomConferencingConnectionRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final AvailabilityOverrideRepository availabilityOverrideRepository;
    private final EventTypeRepository eventTypeRepository;
    private final HostDraftRepository hostDraftRepository;
    private final BookingExperienceRepository bookingExperienceRepository;
    private final FormRepository formRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final ParticipantSetupRequestRepository participantSetupRequestRepository;
    private final AuthIdentityRepository authIdentityRepository;
    private final DeletedAccountTombstoneRepository deletedAccountTombstoneRepository;
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
     * Not itself transactional: each phase below (markRunning, the executeDeletion steps,
     * finalizeDeletion) opens and commits its own transaction. Provider cleanup strategies
     * (e.g. CalendarOAuthService.disconnectGoogle) write via REQUIRES_NEW sub-transactions
     * that commit independently; wrapping this whole method in one transaction previously
     * meant later steps could hold stale, pre-bump versions of rows those sub-transactions
     * had already committed, causing an ObjectOptimisticLockingFailureException — which also
     * poisoned the transaction so even the failure-recording save failed the same way,
     * leaving jobs stuck in RUNNING forever. Committing each phase separately means every
     * step always reads current DB state and a failure anywhere can still be recorded.
     */
    protected void process(UUID jobId) {
        AccountDeletionJob job = markRunning(jobId);
        if (job == null) {
            return;
        }
        log.info("ACCOUNT_DELETION_STARTED userId={} jobId={} attempt={}", job.getUserId(), job.getId(), job.getAttemptCount());

        try {
            executeDeletion(job.getUserId());
            markCompleted(jobId);
            log.info("ACCOUNT_DELETION_COMPLETED userId={} jobId={}", job.getUserId(), job.getId());
        } catch (RuntimeException ex) {
            log.warn("ACCOUNT_DELETION_FAILED userId={} jobId={} message={}", job.getUserId(), job.getId(), ex.getMessage(), ex);
            markJobFailed(job.getId(), job.getAttemptCount(), ex);
        }
    }

    @Transactional
    protected AccountDeletionJob markRunning(UUID jobId) {
        AccountDeletionJob job = accountDeletionJobRepository.findById(jobId).orElse(null);
        if (job == null || !EnumSet.of(AccountDeletionJobStatus.QUEUED, AccountDeletionJobStatus.FAILED).contains(job.getStatus())) {
            return null;
        }
        job.setStatus(AccountDeletionJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setAttemptCount(job.getAttemptCount() + 1);
        job.setNextAttemptAt(null);
        job.setLastErrorCode(null);
        job.setLastErrorMessage(null);
        return accountDeletionJobRepository.save(job);
    }

    @Transactional
    protected void markCompleted(UUID jobId) {
        AccountDeletionJob job = accountDeletionJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(AccountDeletionJobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        accountDeletionJobRepository.save(job);
    }

    @Transactional
    protected void markJobFailed(UUID jobId, int attemptCount, RuntimeException ex) {
        AccountDeletionJob job = accountDeletionJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(AccountDeletionJobStatus.FAILED);
        job.setLastErrorCode(ex.getClass().getSimpleName());
        job.setLastErrorMessage(truncate(ex.getMessage()));
        job.setNextAttemptAt(Instant.now().plus(Duration.ofMinutes(Math.min(30, Math.max(1, attemptCount)))));
        accountDeletionJobRepository.save(job);
    }

    private void executeDeletion(UUID userId) {
        refreshTokenService.deleteByUserId(userId);
        cancelFutureBookings(userId);
        cancelFutureSessions(userId);
        cleanupProviders(userId);
        deleteProviderRecords(userId);
        deleteUserOwnedObjects(userId);
        finalizeDeletion(userId);
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

    @Transactional
    protected void deleteProviderRecords(UUID userId) {
        List<UUID> connectionIds = calendarConnectionRepository.findByUserIdAndStatusNot(userId, CalendarConnectionStatus.DISCONNECTED)
                .stream()
                .map(CalendarConnection::getId)
                .toList();
        if (!connectionIds.isEmpty()) {
            calendarConnectionCalendarRepository.deleteByConnectionIds(connectionIds);
            calendarConnectionSyncCursorRepository.deleteByConnectionIds(connectionIds);
        }
        calendarConnectionRepository.deleteByUserId(userId);
        zoomConferencingConnectionRepository.deleteByUserId(userId);
    }

    @Transactional
    protected void deleteUserOwnedObjects(UUID userId) {
        Instant deletedAt = Instant.now();
        availabilityRuleRepository.deleteByUserId(userId);
        availabilityOverrideRepository.deleteByUserId(userId);
        bookingExperienceRepository.softDeleteByOwnerId(userId, deletedAt);
        formRepository.softDeleteByOwnerId(userId, deletedAt);
        eventTypeRepository.softDeleteByUserId(userId, deletedAt);
        hostDraftRepository.deleteByClaimedOrShadowUserId(userId);
        participantSetupRequestRepository.deleteByOwnerOrTargetUserId(userId);
        teamMemberRepository.deleteByUserId(userId);
    }

    @Transactional
    protected void finalizeDeletion(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        List<AuthIdentity> identities = authIdentityRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Instant deletedAt = Instant.now();

        if (user != null && user.getEmail() != null && !user.getEmail().isBlank()) {
            String normalizedEmail = user.getEmail().trim().toLowerCase(Locale.ROOT);
            DeletedAccountTombstone tombstone = deletedAccountTombstoneRepository.findByNormalizedEmail(normalizedEmail)
                    .orElseGet(DeletedAccountTombstone::new);
            tombstone.setNormalizedEmail(normalizedEmail);
            tombstone.setDeletedAt(deletedAt);
            deletedAccountTombstoneRepository.save(tombstone);
        }

        for (AuthIdentity identity : identities) {
            DeletedAccountTombstone tombstone = deletedAccountTombstoneRepository
                    .findByProviderAndProviderUserId(identity.getProvider(), identity.getProviderUserId())
                    .orElseGet(DeletedAccountTombstone::new);
            tombstone.setProvider(identity.getProvider());
            tombstone.setProviderUserId(identity.getProviderUserId());
            tombstone.setDeletedAt(deletedAt);
            if (user != null && user.getEmail() != null && !user.getEmail().isBlank()) {
                tombstone.setNormalizedEmail(user.getEmail().trim().toLowerCase(Locale.ROOT));
            }
            deletedAccountTombstoneRepository.save(tombstone);
        }

        authIdentityRepository.deleteByUserId(userId);
        if (user != null) {
            userRepository.delete(user);
        }
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }
}
