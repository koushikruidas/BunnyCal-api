package io.bunnycal.booking.idempotency;

import io.bunnycal.booking.contract.BookingContracts;
import io.bunnycal.common.time.TimeSource;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class IdempotencyKeyCleanupJob {

    private static final String ABANDONED_RESPONSE_JSON =
            "{\"success\":false,\"data\":null,\"error\":{\"code\":\"IDEMPOTENCY_ABANDONED\",\"message\":\"Request processing was abandoned; retry with a new Idempotency-Key.\"}}";

    private final IdempotencyKeyRepository repository;
    private final TimeSource timeSource;

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void reap() {
        Instant now = timeSource.now();
        Instant cutoff = now.minus(BookingContracts.IDEMPOTENCY_PROCESSING_TIMEOUT);
        repository.reapStuckRows(cutoff, ABANDONED_RESPONSE_JSON, now);
    }

    @Scheduled(cron = "0 */15 * * * *")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = timeSource.now().minus(BookingContracts.IDEMPOTENCY_KEY_TTL);
        repository.deleteExpired(cutoff);
    }
}
