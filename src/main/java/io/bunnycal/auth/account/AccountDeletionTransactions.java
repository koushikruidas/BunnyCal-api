package io.bunnycal.auth.account;

import io.bunnycal.auth.domain.identity.AuthIdentity;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.repository.AvailabilityOverrideRepository;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarConnectionSyncCursorRepository;
import io.bunnycal.conferencing.repository.ZoomConferencingConnectionRepository;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.repository.FormRepository;
import io.bunnycal.team.repository.ParticipantSetupRequestRepository;
import io.bunnycal.team.repository.TeamMemberRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional units of an account deletion, split out of {@link AccountDeletionWorker}.
 *
 * <p>They live in their own bean deliberately. {@code @Transactional} is applied by a Spring
 * proxy, and a proxy only intercepts calls that arrive from outside the bean — a method calling
 * a sibling method on {@code this} bypasses it entirely and runs with no transaction. When these
 * methods lived on the worker and were invoked from its own {@code process()}, their annotations
 * were silently inert: Spring Data's simple repository methods open their own transaction, so
 * those appeared to work, but the {@code @Modifying} bulk deletes here demand a caller-supplied
 * transaction and failed with {@code TransactionRequiredException} ("No active transaction for
 * update or delete query"). Injecting this bean into the worker forces every call through the
 * proxy, so the annotations take effect.
 *
 * <p>Each method is a separate transaction rather than one spanning the whole deletion. Provider
 * cleanup (e.g. {@code CalendarOAuthService.disconnectGoogle}) writes through
 * {@code REQUIRES_NEW} sub-transactions that commit on their own and bump {@code @Version}
 * columns; a single enclosing transaction would still be holding those rows at their pre-bump
 * version, and the next flush would fail with an {@code ObjectOptimisticLockingFailureException}
 * — which then poisoned the transaction so even the failure-recording save failed, stranding the
 * job in {@code RUNNING} forever. Committing phase by phase means each one reads current state
 * and a failure can always be recorded.
 *
 * <p>The trade-off is that a deletion is no longer atomic; a crash mid-way leaves earlier phases
 * committed. Every phase is therefore idempotent so a retry can resume: bulk deletes no-op on
 * already-deleted rows, and {@link #finalizeDeletion} upserts tombstones and tolerates the user
 * and identities already being gone.
 */
@Component
@RequiredArgsConstructor
public class AccountDeletionTransactions {

    private final AccountDeletionJobRepository accountDeletionJobRepository;
    private final UserRepository userRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarConnectionCalendarRepository calendarConnectionCalendarRepository;
    private final CalendarConnectionSyncCursorRepository calendarConnectionSyncCursorRepository;
    private final ZoomConferencingConnectionRepository zoomConferencingConnectionRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final AvailabilityOverrideRepository availabilityOverrideRepository;
    private final EventTypeRepository eventTypeRepository;
    private final BookingExperienceRepository bookingExperienceRepository;
    private final FormRepository formRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final ParticipantSetupRequestRepository participantSetupRequestRepository;
    private final AuthIdentityRepository authIdentityRepository;
    private final DeletedAccountTombstoneRepository deletedAccountTombstoneRepository;

    /** Claims the job for this attempt, or returns null if another attempt already has it. */
    @Transactional
    public AccountDeletionJob markRunning(UUID jobId) {
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
    public void markCompleted(UUID jobId) {
        AccountDeletionJob job = accountDeletionJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(AccountDeletionJobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        accountDeletionJobRepository.save(job);
    }

    @Transactional
    public void markFailed(UUID jobId, int attemptCount, RuntimeException ex) {
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

    @Transactional
    public void deleteProviderRecords(UUID userId) {
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
    public void deleteUserOwnedObjects(UUID userId) {
        Instant deletedAt = Instant.now();
        availabilityRuleRepository.deleteByUserId(userId);
        availabilityOverrideRepository.deleteByUserId(userId);
        bookingExperienceRepository.softDeleteByOwnerId(userId, deletedAt);
        formRepository.softDeleteByOwnerId(userId, deletedAt);
        eventTypeRepository.softDeleteByUserId(userId, deletedAt);
        participantSetupRequestRepository.deleteByOwnerOrTargetUserId(userId);
        teamMemberRepository.deleteByUserId(userId);
    }

    @Transactional
    public void finalizeDeletion(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        List<AuthIdentity> identities = authIdentityRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Instant deletedAt = Instant.now();
        String normalizedEmail = normalizedEmail(user);

        // normalized_email and (provider, provider_user_id) each carry a unique index, so the
        // email may live on at most one row. Attach it to the first identity's tombstone and
        // leave the rest email-less; only when there is no identity at all does the email need
        // a row of its own.
        boolean emailClaimed = false;
        for (AuthIdentity identity : identities) {
            DeletedAccountTombstone tombstone = deletedAccountTombstoneRepository
                    .findByProviderAndProviderUserId(identity.getProvider(), identity.getProviderUserId())
                    .orElseGet(DeletedAccountTombstone::new);
            tombstone.setProvider(identity.getProvider());
            tombstone.setProviderUserId(identity.getProviderUserId());
            tombstone.setDeletedAt(deletedAt);
            if (normalizedEmail != null && !emailClaimed && emailFreeFor(normalizedEmail, tombstone)) {
                tombstone.setNormalizedEmail(normalizedEmail);
                emailClaimed = true;
            }
            deletedAccountTombstoneRepository.save(tombstone);
        }

        if (normalizedEmail != null && !emailClaimed) {
            DeletedAccountTombstone tombstone = deletedAccountTombstoneRepository.findByNormalizedEmail(normalizedEmail)
                    .orElseGet(DeletedAccountTombstone::new);
            tombstone.setNormalizedEmail(normalizedEmail);
            tombstone.setDeletedAt(deletedAt);
            deletedAccountTombstoneRepository.save(tombstone);
        }

        authIdentityRepository.deleteByUserId(userId);
        if (user != null) {
            userRepository.delete(user);
        }
    }

    /** True when the email is unclaimed, or already claimed by {@code target} itself. */
    private boolean emailFreeFor(String normalizedEmail, DeletedAccountTombstone target) {
        return deletedAccountTombstoneRepository.findByNormalizedEmail(normalizedEmail)
                .map(existing -> existing.getId() != null && existing.getId().equals(target.getId()))
                .orElse(true);
    }

    private static String normalizedEmail(User user) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return null;
        }
        return user.getEmail().trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }
}
