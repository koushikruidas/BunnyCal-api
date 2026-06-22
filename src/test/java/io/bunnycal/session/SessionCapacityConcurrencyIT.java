package io.bunnycal.session;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that concurrent joinSession + confirmRegistration calls never exceed capacity,
 * and that confirmed_count matches exactly the number of CONFIRMED registrations.
 */
class SessionCapacityConcurrencyIT extends AbstractSessionIT {

    @Autowired
    private SessionService sessionService;

    private static final int CAPACITY = 3;
    private static final int CONTENDERS = CAPACITY + 5; // 8 threads fight for 3 seats

    @Test
    void concurrentJoinAndConfirm_neverExceedsCapacity() throws Exception {
        User host = createHost();
        EventType eventType = createGroupEventType(host.getId(), CAPACITY);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);

        AtomicInteger joinSuccesses = new AtomicInteger();
        AtomicInteger confirmSuccesses = new AtomicInteger();
        AtomicInteger capacityRejections = new AtomicInteger();
        List<Future<UUID>> futures = new ArrayList<>();

        for (int i = 0; i < CONTENDERS; i++) {
            String email = "attendee-" + i + "@test.com";
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();

                JoinSessionResult join;
                try {
                    join = sessionService.joinSession(
                            host.getId(), eventType.getId(), start, end, CAPACITY,
                            email, "Attendee", Duration.ofMinutes(5));
                    joinSuccesses.incrementAndGet();
                } catch (CustomException e) {
                    // Session was already FULL by the time this thread joined.
                    capacityRejections.incrementAndGet();
                    return null;
                }

                try {
                    sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());
                    confirmSuccesses.incrementAndGet();
                    return join.registrationId();
                } catch (CustomException e) {
                    capacityRejections.incrementAndGet();
                    return null;
                }
            }));
        }

        ready.await();
        go.countDown();

        List<UUID> confirmedIds = new ArrayList<>();
        for (Future<UUID> f : futures) {
            UUID id = f.get();
            if (id != null) confirmedIds.add(id);
        }
        pool.shutdown();

        // Exactly CAPACITY threads should have confirmed successfully.
        assertThat(confirmSuccesses.get())
                .as("Exactly capacity registrations must be confirmed")
                .isEqualTo(CAPACITY);

        // Find the session and assert DB state.
        UUID sessionId = confirmedIds.stream().findFirst().map(regId ->
                (UUID) jdbc.queryForObject(
                        "SELECT session_id FROM session_registrations WHERE id = ?",
                        UUID.class, regId)).orElseThrow();

        Map<String, Object> sessionRow = querySession(sessionId);
        assertThat(sessionRow.get("status")).isEqualTo("FULL");
        assertThat(((Number) sessionRow.get("confirmed_count")).intValue()).isEqualTo(CAPACITY);

        // DB confirmed_count must match actual CONFIRMED rows.
        int actualConfirmed = countRegistrationsByStatus(sessionId, "CONFIRMED");
        assertThat(actualConfirmed).isEqualTo(CAPACITY);

        // Total CONTENDERS = confirmed + rejected (via capacity or join rejection).
        assertThat(confirmSuccesses.get() + capacityRejections.get()).isEqualTo(CONTENDERS);
    }
}
