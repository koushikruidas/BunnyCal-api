package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BookingCompletionSloTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T12:00:00Z");

    private SimpleMeterRegistry registry;
    private BookingService service;
    private BookingRepository bookingRepo;
    private TimeSource timeSource;

    @BeforeEach
    void setUp() {
        registry    = new SimpleMeterRegistry();
        bookingRepo = mock(BookingRepository.class);
        timeSource  = mock(TimeSource.class);
        when(timeSource.now()).thenReturn(FIXED_NOW);
        when(bookingRepo.countByStatus(any())).thenReturn(0L);

        service = new BookingService(
                mock(UserRepository.class),
                bookingRepo,
                mock(OutboxPublisher.class),
                timeSource,
                registry);
    }

    // ─────────────────────────────────────────────────────────────
    // completionTimer records when a terminal state is reached
    // ─────────────────────────────────────────────────────────────

    @Test
    void completionTimer_recordsOnCompleteBooking() {
        UUID id = UUID.randomUUID();
        when(bookingRepo.updateStatus(eq(id), eq("CONFIRMED"), eq("COMPLETED"), anyLong()))
                .thenReturn(1);
        when(bookingRepo.findCreatedAtById(id))
                .thenReturn(Optional.of(FIXED_NOW.minusSeconds(2)));

        service.completeBooking(id, 0L);

        Timer timer = registry.find("booking.completion.latency.seconds").timer();
        assertEquals(1, timer.count(), "completionTimer must record exactly once on COMPLETED");
    }

    @Test
    void completionTimer_recordsOnCancelPending() {
        UUID id = UUID.randomUUID();
        when(bookingRepo.updateStatus(eq(id), eq("PENDING"), eq("CANCELLED"), anyLong()))
                .thenReturn(1);
        when(bookingRepo.findCreatedAtById(id))
                .thenReturn(Optional.of(FIXED_NOW.minusSeconds(1)));

        service.cancelPendingBooking(id, 0L);

        Timer timer = registry.find("booking.completion.latency.seconds").timer();
        assertEquals(1, timer.count(), "completionTimer must record exactly once on CANCELLED");
    }

    @Test
    void completionTimer_recordsOnExpireBooking() {
        UUID id = UUID.randomUUID();
        when(bookingRepo.expireIfPendingAndExpired(eq(id), anyLong())).thenReturn(1);
        when(bookingRepo.findCreatedAtById(id))
                .thenReturn(Optional.of(FIXED_NOW.minusSeconds(3)));

        service.expireBooking(id, 0L);

        Timer timer = registry.find("booking.completion.latency.seconds").timer();
        assertEquals(1, timer.count(), "completionTimer must record exactly once on EXPIRED");
    }

    // ─────────────────────────────────────────────────────────────
    // CONFIRMED is NOT terminal — timer must not fire
    // ─────────────────────────────────────────────────────────────

    @Test
    void completionTimer_doesNotRecordOnConfirmBooking() {
        UUID id = UUID.randomUUID();
        when(bookingRepo.updateStatusAndCalendarSequence(eq(id), eq("PENDING"), eq("CONFIRMED"), anyLong()))
                .thenReturn(1);
        when(bookingRepo.findAnyById(id)).thenReturn(Optional.empty());

        service.confirmBooking(id, 0L);

        Timer timer = registry.find("booking.completion.latency.seconds").timer();
        assertEquals(0, timer.count(),
                "completionTimer must NOT record for CONFIRMED (non-terminal state)");
    }

    // ─────────────────────────────────────────────────────────────
    // P16: silent SLO recording failures are now counted, not just logged
    // ─────────────────────────────────────────────────────────────

    @Test
    void completionLatencyFailureCounter_incrementsWhenRepoThrows() {
        UUID id = UUID.randomUUID();
        when(bookingRepo.updateStatus(eq(id), eq("CONFIRMED"), eq("COMPLETED"), anyLong()))
                .thenReturn(1);
        // Simulate a transient DB error during the created_at lookup. The terminal
        // CAS has already happened, so the business operation must still succeed —
        // but the failure must be visible via the counter so SLO breakage doesn't
        // hide behind broken instrumentation.
        when(bookingRepo.findCreatedAtById(id))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated DB blip"));

        service.completeBooking(id, 0L);

        Counter failures = registry.find("booking.completion.latency.record.failed.total").counter();
        assertEquals(1.0, failures.count(), 0.001,
                "failure counter must increment when the SLO record path throws");
        Timer timer = registry.find("booking.completion.latency.seconds").timer();
        assertEquals(0, timer.count(),
                "timer must not record when the upstream read failed");
    }

    // ─────────────────────────────────────────────────────────────
    // CAS miss → exception thrown before any metric is recorded
    // ─────────────────────────────────────────────────────────────

    @Test
    void completionTimer_doesNotRecordWhenCasFails() {
        UUID id = UUID.randomUUID();
        when(bookingRepo.updateStatus(eq(id), eq("CONFIRMED"), eq("COMPLETED"), anyLong()))
                .thenReturn(0);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.completeBooking(id, 0L));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());

        Timer timer = registry.find("booking.completion.latency.seconds").timer();
        assertEquals(0, timer.count(), "completionTimer must not record when CAS returns 0 rows");
    }

    // ─────────────────────────────────────────────────────────────
    // Negative duration guard: clock skew must not produce a recording
    // ─────────────────────────────────────────────────────────────

    @Test
    void completionTimer_doesNotRecordOnNegativeDuration() {
        UUID id = UUID.randomUUID();
        when(bookingRepo.updateStatus(eq(id), eq("CONFIRMED"), eq("COMPLETED"), anyLong()))
                .thenReturn(1);
        // created_at is in the future relative to timeSource.now() → negative duration
        when(bookingRepo.findCreatedAtById(id))
                .thenReturn(Optional.of(FIXED_NOW.plusSeconds(1)));

        service.completeBooking(id, 0L);

        Timer timer = registry.find("booking.completion.latency.seconds").timer();
        assertEquals(0, timer.count(),
                "completionTimer must not record when duration is negative (clock skew)");
    }

    // ─────────────────────────────────────────────────────────────
    // SLI counters: within-SLO increments only when duration <= 5 s
    // ─────────────────────────────────────────────────────────────

    @Test
    void sliCounters_bothIncrementWhenWithinSlo() {
        UUID id = UUID.randomUUID();
        when(bookingRepo.updateStatus(eq(id), eq("CONFIRMED"), eq("COMPLETED"), anyLong()))
                .thenReturn(1);
        when(bookingRepo.findCreatedAtById(id))
                .thenReturn(Optional.of(FIXED_NOW.minusSeconds(2)));

        service.completeBooking(id, 0L);

        Counter completed = registry.find("booking.completed.total").counter();
        Counter withinSlo = registry.find("booking.completed.within_slo.total").counter();
        assertEquals(1.0, completed.count(), 0.001);
        assertEquals(1.0, withinSlo.count(), 0.001,
                "booking.completed.within_slo.total must increment when duration < 5 s");
    }

    @Test
    void sliCounters_withinSloIncrementsAtExactlyFiveSeconds() {
        UUID id = UUID.randomUUID();
        when(bookingRepo.updateStatus(eq(id), eq("CONFIRMED"), eq("COMPLETED"), anyLong()))
                .thenReturn(1);
        // Exactly at the SLO boundary — must be counted as within-SLO (≤ not <)
        when(bookingRepo.findCreatedAtById(id))
                .thenReturn(Optional.of(FIXED_NOW.minusSeconds(5)));

        service.completeBooking(id, 0L);

        Counter withinSlo = registry.find("booking.completed.within_slo.total").counter();
        assertEquals(1.0, withinSlo.count(), 0.001,
                "booking.completed.within_slo.total must count exactly-5-second completions");
    }

    @Test
    void sliCounters_onlyCompletedIncrementWhenOverSlo() {
        UUID id = UUID.randomUUID();
        when(bookingRepo.updateStatus(eq(id), eq("CONFIRMED"), eq("COMPLETED"), anyLong()))
                .thenReturn(1);
        when(bookingRepo.findCreatedAtById(id))
                .thenReturn(Optional.of(FIXED_NOW.minusSeconds(10)));

        service.completeBooking(id, 0L);

        Counter completed = registry.find("booking.completed.total").counter();
        Counter withinSlo = registry.find("booking.completed.within_slo.total").counter();
        assertEquals(1.0, completed.count(), 0.001);
        assertEquals(0.0, withinSlo.count(), 0.001,
                "booking.completed.within_slo.total must NOT increment when duration > 5 s");
    }
}
