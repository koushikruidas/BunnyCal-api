package io.bunnycal.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionService;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BookingSyncReconciler must never pick up a group session's sync job.
 *
 * <p>It observes through CalendarProviderClient, which resolves the host with
 * {@code bookingRepository.findAnyById(internalRefId)}. A session id is not a booking id, so a
 * SESSION job reaching the reconciler threw 404 -> INVALID_REQUEST -> PERMANENT_FAILURE and the
 * session was recorded PROVIDER_STATE_ORPHANED -- a verdict about our own lookup, not the provider.
 * On the availability grid that stale provider copy then covered the striped hold marking where a
 * rescheduled session had moved to.
 */
class SessionReconcileScopeIT extends AbstractSessionIT {

    @Autowired private SessionService sessionService;
    @Autowired private CalendarSyncJobRepository syncJobRepository;

    @Test
    void syncedCandidates_excludeSessionJobs() {
        var host = createHost();
        var eventType = createGroupEventType(host.getId(), 2);
        Instant start = nextHour();

        JoinSessionResult join = sessionService.joinSession(
                host.getId(), eventType.getId(), start, start.plusSeconds(3600), 2,
                "a@test.com", "Alice", null);
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        long sessionVersion = ((Number) querySession(join.sessionId()).get("version")).longValue();
        UUID jobId = UUID.randomUUID();
        inTx(() -> syncJobRepository.upsertPendingJob(
                jobId, "SESSION", join.sessionId(), "google", "UPDATE",
                "ext-session-1", host.getId(), null, sessionVersion));

        // The state a session job sits in once SessionSyncWorker has written it through: SYNCED,
        // no error, and due -- which is exactly what the reconciler's candidate query selects on.
        jdbc.update("UPDATE calendar_sync_jobs SET status = 'SYNCED', last_error = NULL,"
                + " next_retry_at = NOW() - INTERVAL '1 minute' WHERE id = ?", jobId);

        assertThat(syncJobRepository.findSyncedCandidates(50))
                .as("a SESSION job must not be handed to the booking reconciler")
                .noneMatch(job -> jobId.equals(job.getId()));
    }

    @Test
    void syncedCandidates_stillIncludeBookingJobs() {
        var host = createHost();
        UUID bookingRefId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        inTx(() -> syncJobRepository.upsertPendingJob(
                jobId, "BOOKING", bookingRefId, "google", "UPDATE",
                "ext-booking-1", host.getId(), null, 0L));
        jdbc.update("UPDATE calendar_sync_jobs SET status = 'SYNCED', last_error = NULL,"
                + " next_retry_at = NOW() - INTERVAL '1 minute' WHERE id = ?", jobId);

        // The scope narrowed to BOOKING, so the reconciler must still see its own work: a filter
        // that excluded everything would pass the test above for the wrong reason.
        assertThat(syncJobRepository.findSyncedCandidates(50))
                .as("BOOKING jobs remain the reconciler's candidates")
                .anyMatch(job -> jobId.equals(job.getId()));
    }
}
