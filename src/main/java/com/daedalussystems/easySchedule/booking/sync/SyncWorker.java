package com.daedalussystems.easySchedule.booking.sync;

import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.ClaimOutcome;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.FinalizeOutcome;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.MappingKey;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.MappingState;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.TransitionOutcome;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SyncWorker {
    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

    private final CalendarEventMappingRepository mappingRepository;
    private final FencingTokenGenerator tokenGenerator;
    private final CalendarProviderClient providerClient;
    private final MeterRegistry meterRegistry;
    private final String workerId = "sync-worker-" + UUID.randomUUID();
    private static final int RECONCILE_BATCH_SIZE = 50;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final int MAX_SYNC_RETRIES = 10;
    private static final Duration RETRY_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration RETRY_MAX_BACKOFF = Duration.ofMinutes(5);

    private final Counter claimClaimedCounter;
    private final Counter claimRejectedCounter;
    private final Counter providerFailureCounter;
    private final Counter finalizeSuccessCounter;
    private final Counter finalizeAlreadyCompletedCounter;
    private final Counter finalizeStaleOrNotOwnerCounter;
    private final Counter finalizeSplitBrainCounter;
    private final Counter failedPermanentCounter;
    private final DistributionSummary retryDelayMsSummary;
    private final DistributionSummary retryAttemptSummary;
    private final Timer timeToSuccessTimer;

    public SyncWorker(CalendarEventMappingRepository mappingRepository,
                      FencingTokenGenerator tokenGenerator,
                      CalendarProviderClient providerClient,
                      MeterRegistry meterRegistry) {
        this.mappingRepository = mappingRepository;
        this.tokenGenerator = tokenGenerator;
        this.providerClient = providerClient;
        this.meterRegistry = meterRegistry;
        this.claimClaimedCounter = meterRegistry.counter("sync.claim.claimed");
        this.claimRejectedCounter = meterRegistry.counter("sync.claim.rejected");
        this.providerFailureCounter = meterRegistry.counter("sync.provider.failure");
        this.finalizeSuccessCounter = meterRegistry.counter("sync.finalize.success");
        this.finalizeAlreadyCompletedCounter = meterRegistry.counter("sync.finalize.already_completed");
        this.finalizeStaleOrNotOwnerCounter = meterRegistry.counter("sync.finalize.stale_or_not_owner");
        this.finalizeSplitBrainCounter = meterRegistry.counter("sync.finalize.split_brain");
        this.failedPermanentCounter = meterRegistry.counter("sync.failed.permanent");
        this.retryDelayMsSummary = DistributionSummary.builder("sync.retry.delay.ms")
                .baseUnit("milliseconds")
                .register(meterRegistry);
        this.retryAttemptSummary = DistributionSummary.builder("sync.retry.attempt")
                .register(meterRegistry);
        this.timeToSuccessTimer = Timer.builder("sync.time_to_success.ms")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void processBookingSync(UUID bookingId, String provider) {
        long token = tokenGenerator.nextToken();

        ClaimOutcome claimOutcome = mappingRepository.claimBookingForSync(bookingId, provider, token, workerId);
        countClaim(claimOutcome, provider);
        log.debug("calendar sync claim bookingId={} provider={} token={} workerId={} outcome={}",
                bookingId, provider, token, workerId, claimOutcome);
        if (claimOutcome != ClaimOutcome.CLAIMED) {
            return;
        }

        Optional<MappingState> maybeState = mappingRepository.findMappingState(bookingId, provider);
        if (maybeState.isEmpty()) {
            return;
        }

        MappingState state = maybeState.get();
        if (state.syncToken() != token || !workerId.equals(state.claimedBy())) {
            log.debug("calendar sync ownership drift bookingId={} provider={} token={} rowToken={} workerId={} rowWorker={}",
                    bookingId, provider, token, state.syncToken(), workerId, state.claimedBy());
            return;
        }
        if ("CREATED".equals(state.status())) {
            return;
        }
        if (state.externalEventId() != null) {
            return;
        }

        String externalEventId;
        try {
            externalEventId = providerClient.createEvent(bookingId, provider, idempotencyKey(bookingId, provider));
        } catch (Exception ex) {
            log.warn("calendar sync provider failure bookingId={} provider={} token={} workerId={}",
                    bookingId, provider, token, workerId, ex);
            providerFailureCounter.increment();
            String normalizedError = normalizeError(ex);
            int nextAttempt = state.attemptCount() + 1;
            retryAttemptSummary.record(nextAttempt);
            TransitionOutcome failOutcome;
            if (nextAttempt >= MAX_SYNC_RETRIES) {
                failOutcome = mappingRepository.markFailedPermanent(
                        bookingId, provider, workerId, normalizedError, token);
                failedPermanentCounter.increment();
            } else {
                Duration backoff = computeBackoff(nextAttempt);
                retryDelayMsSummary.record(backoff.toMillis());
                Instant nextRetryAt = Instant.now().plus(backoff);
                failOutcome = mappingRepository.markFailed(
                        bookingId, provider, workerId, normalizedError, token, nextRetryAt);
            }
            log.debug("calendar sync mark failed bookingId={} provider={} token={} workerId={} outcome={}",
                    bookingId, provider, token, workerId, failOutcome);
            return;
        }

        FinalizeOutcome finalizeOutcome = mappingRepository.updateMappingWithEventId(
                bookingId, provider, externalEventId, token, workerId);
        countFinalize(finalizeOutcome, provider);
        log.debug("calendar sync finalize bookingId={} provider={} token={} workerId={} outcome={}",
                bookingId, provider, token, workerId, finalizeOutcome);

        if (finalizeOutcome == FinalizeOutcome.SUCCESS && state.claimedAt() != null) {
            Duration elapsed = Duration.between(state.claimedAt(), Instant.now());
            if (!elapsed.isNegative()) {
                timeToSuccessTimer.record(elapsed);
            }
        }

        if (finalizeOutcome == FinalizeOutcome.SPLIT_BRAIN_DETECTED) {
            log.error("calendar sync split-brain bookingId={} provider={} workerId={} token={}",
                    bookingId, provider, workerId, token);
            throw new IllegalStateException("Split-brain detected during calendar sync finalize");
        }
    }

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(
            name = "calendar-sync-reconcile",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT3S"
    )
    public void reconcileFailedMappings() {
        log.debug("reconcile lock acquired workerId={}", workerId);
        long reclaimToken = tokenGenerator.nextToken();
        mappingRepository.reclaimStuckClaimed(
                workerId,
                Instant.now().minusSeconds(30),
                reclaimToken,
                RECONCILE_BATCH_SIZE
        );

        for (MappingKey key : mappingRepository.findFailedCandidates(RECONCILE_BATCH_SIZE, Instant.now())) {
            processBookingSync(key.bookingId(), key.provider());
        }
    }

    private static String normalizeError(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        if (message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }

    private static Duration computeBackoff(int attempt) {
        long baseMs = RETRY_INITIAL_BACKOFF.toMillis();
        long maxMs = RETRY_MAX_BACKOFF.toMillis();
        int exponent = Math.max(0, attempt - 1);
        long delay = baseMs;
        for (int i = 0; i < exponent; i++) {
            if (delay >= maxMs / 2) {
                delay = maxMs;
                break;
            }
            delay *= 2;
        }
        delay = Math.min(delay, maxMs);
        long half = Math.max(1L, delay / 2);
        long jitter = ThreadLocalRandom.current().nextLong(half);
        return Duration.ofMillis(half + jitter);
    }

    private static String idempotencyKey(UUID bookingId, String provider) {
        return provider + ":" + bookingId;
    }

    private void countClaim(ClaimOutcome outcome, String provider) {
        Counter counter = meterRegistry.counter("sync.claim.count",
                List.of(Tag.of("provider", provider), Tag.of("outcome", outcome.name())));
        counter.increment();
        if (outcome == ClaimOutcome.CLAIMED) {
            claimClaimedCounter.increment();
        } else {
            claimRejectedCounter.increment();
        }
    }

    private void countFinalize(FinalizeOutcome outcome, String provider) {
        Counter counter = meterRegistry.counter("sync.finalize.count",
                List.of(Tag.of("provider", provider), Tag.of("outcome", outcome.name())));
        counter.increment();
        switch (outcome) {
            case SUCCESS -> finalizeSuccessCounter.increment();
            case ALREADY_COMPLETED -> finalizeAlreadyCompletedCounter.increment();
            case STALE_OR_NOT_OWNER -> finalizeStaleOrNotOwnerCounter.increment();
            case SPLIT_BRAIN_DETECTED -> finalizeSplitBrainCounter.increment();
        }
    }
}
