package com.daedalussystems.easySchedule.booking.idempotency;

import com.daedalussystems.easySchedule.booking.contract.BookingContracts;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.time.TimeSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    static {
        if (BookingContracts.IDEMPOTENCY_POLL_TOTAL.compareTo(BookingContracts.IDEMPOTENCY_PROCESSING_TIMEOUT) >= 0) {
            throw new IllegalStateException("IDEMPOTENCY_POLL_TOTAL must be less than IDEMPOTENCY_PROCESSING_TIMEOUT");
        }
    }

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;
    private final TimeSource timeSource;
    private final MeterRegistry meterRegistry;
    private final PlatformTransactionManager transactionManager;

    public <T> IdempotencyOutcome execute(
            String key,
            UUID userId,
            String route,
            String requestHash,
            Supplier<ResponseEnvelope<T>> work) {
        Instant start = timeSource.now();
        if (phase1Insert(key, userId, route, requestHash)) {
            return phase2RunAndFinalize(key, userId, route, work);
        }

        IdempotencyKey existing = repository.findByUserIdAndRouteAndKey(userId, route, key)
                .orElseThrow(() -> new CustomException(ErrorCode.IDEMPOTENCY_RACE));

        if (!existing.getRequestHash().equals(requestHash)) {
            outcomeCounter("hash_mismatch").increment();
            throw new CustomException(ErrorCode.IDEMPOTENCY_HASH_MISMATCH);
        }

        if (existing.getStatus().isTerminal()) {
            outcomeCounter("replayed").increment();
            replayTimer().record(Duration.between(start, timeSource.now()));
            return replay(existing);
        }

        IdempotencyKey terminal = pollForTerminal(userId, route, key);
        if (terminal == null) {
            outcomeCounter("in_progress").increment();
            throw new CustomException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        }

        outcomeCounter("replayed").increment();
        replayTimer().record(Duration.between(start, timeSource.now()));
        return replay(terminal);
    }

    private boolean phase1Insert(String key, UUID userId, String route, String requestHash) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = timeSource.now();
            int inserted = repository.tryInsert(UUID.randomUUID(), key, userId, route, requestHash, now);
            return inserted == 1;
        }));
    }

    private <T> IdempotencyOutcome phase2RunAndFinalize(
            String key,
            UUID userId,
            String route,
            Supplier<ResponseEnvelope<T>> work) {
        try {
            // Steps 5-7 commit atomically: booking insert + outbox event +
            // idempotency finalization. bookingService.createBooking() is
            // @Transactional(REQUIRED) and joins this outer transaction.
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRED);

            IdempotencyOutcome outcome = tx.execute(status -> {
                ResponseEnvelope<T> response = work.get();
                String body = serializeBody(response.body());

                if (body.getBytes().length > BookingContracts.MAX_CACHED_RESPONSE_BYTES) {
                    body = IdempotencyOutcome.oversizedReplayBody();
                }

                int updated = repository.finalizeByScope(
                        userId,
                        route,
                        key,
                        IdempotencyStatus.COMPLETED,
                        response.httpStatus(),
                        body,
                        timeSource.now());

                if (updated == 0) {
                    outcomeCounter("race").increment();
                    finalizeRaceCounter().increment();
                    log.warn("Idempotency finalize race detected for userId={}, route={}, key={}", userId, route, key);
                    throw new CustomException(ErrorCode.IDEMPOTENCY_RACE);
                }

                return new IdempotencyOutcome.Fresh<>(response.httpStatus(), response.body());
            });

            outcomeCounter("fresh").increment();
            return outcome;
        } catch (CustomException ex) {
            if (shouldCacheFailure(ex)) {
                phase3StoreFailure(userId, route, key, ex);
            }
            throw ex;
        }
    }

    private void phase3StoreFailure(UUID userId, String route, String key, CustomException ex) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> repository.finalizeByScope(
                userId,
                route,
                key,
                IdempotencyStatus.FAILED,
                mapStatus(ex.getErrorCode()),
                serializeBody(ApiResponse.error(ex.getErrorCode(), ex.getMessage())),
                timeSource.now()));
    }

    private IdempotencyKey pollForTerminal(UUID userId, String route, String key) {
        long deadlineEpochMs = timeSource.now().toEpochMilli() + BookingContracts.IDEMPOTENCY_POLL_TOTAL.toMillis();
        int attempt = 0;

        while (timeSource.now().toEpochMilli() < deadlineEpochMs) {
            IdempotencyKey row = repository.findByUserIdAndRouteAndKey(userId, route, key)
                    .orElse(null);
            if (row != null && row.getStatus().isTerminal()) {
                return row;
            }

            inProgressPollCounter().increment();
            long base = Math.min(
                    BookingContracts.IDEMPOTENCY_POLL_INITIAL.toMillis() * (1L << Math.min(attempt, 10)),
                    BookingContracts.IDEMPOTENCY_POLL_MAX.toMillis());
            long sleepMillis = ThreadLocalRandom.current().nextLong(base + 1);
            sleep(sleepMillis);
            attempt++;
        }

        return null;
    }

    private IdempotencyOutcome replay(IdempotencyKey key) {
        return new IdempotencyOutcome.Replayed(key.getResponseStatus(), key.getResponseBody());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling idempotency row", ex);
        }
    }

    private String serializeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize idempotency response", ex);
        }
    }

    private boolean shouldCacheFailure(CustomException ex) {
        return mapStatus(ex.getErrorCode()) < 500;
    }

    private int mapStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case IDEMPOTENCY_KEY_REQUIRED, VALIDATION_ERROR -> 400;
            case IDEMPOTENCY_HASH_MISMATCH -> 422;
            // SLOT_ALREADY_BOOKED and TOO_MANY_PENDING_BOOKINGS must map to
            // < 500 so shouldCacheFailure() caches them. Otherwise a same-key
            // retry would re-execute the booking work instead of replaying
            // the cached error response.
            case IDEMPOTENCY_IN_PROGRESS, SLOT_ALREADY_BOOKED -> 409;
            case TOO_MANY_PENDING_BOOKINGS -> 429;
            case UNAUTHORIZED, TOKEN_EXPIRED, TOKEN_INVALID -> 401;
            case FORBIDDEN -> 403;
            case RESOURCE_NOT_FOUND -> 404;
            case IDEMPOTENCY_RACE -> 503;
            default -> 500;
        };
    }

    private Counter outcomeCounter(String outcome) {
        return Counter.builder("idempotency_outcome_total")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private Timer replayTimer() {
        return Timer.builder("idempotency_replay_latency").register(meterRegistry);
    }

    private Counter inProgressPollCounter() {
        return Counter.builder("idempotency_in_progress_polls_total").register(meterRegistry);
    }

    private Counter finalizeRaceCounter() {
        return Counter.builder("idempotency_finalize_race_total").register(meterRegistry);
    }
}
